package com.happycodelucky.backgrounder.ios

import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.AttemptFailureReason
import com.happycodelucky.backgrounder.BackoffPolicy
import com.happycodelucky.backgrounder.DeferralReason
import com.happycodelucky.backgrounder.EphemeralRegistry
import com.happycodelucky.backgrounder.MonitorEvent
import com.happycodelucky.backgrounder.MonitorEventEmitter
import com.happycodelucky.backgrounder.PlatformCapabilities
import com.happycodelucky.backgrounder.ReachabilityGate
import com.happycodelucky.backgrounder.SkipReason
import com.happycodelucky.backgrounder.TaskId
import com.happycodelucky.backgrounder.WorkResult
import com.happycodelucky.backgrounder.WorkerContext
import com.happycodelucky.backgrounder.WorkerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * Pure-logic core of the iOS periodic-dispatch model.
 *
 * The two feeds — [IOSForegroundFeed] (in-process timer, observing
 * `UIApplication` lifecycle) and [IOSBackgroundFeed] (the single library-owned
 * `BGAppRefreshTaskRequest`) — both call into this dispatcher when they wake
 * up. The dispatcher is dispatch-source-agnostic: it doesn't know whether it
 * was woken by an in-process timer or by `BGTaskScheduler`, and it never
 * touches `BGTask.setTaskCompletedWithSuccess` (that's the background feed's
 * concern, since only it owns a `BGTask` instance).
 *
 * **Coalescing contract.** When [dispatchDueWork] is called, the dispatcher:
 *
 *  1. Snapshots the set of due `TaskId`s — periodics whose persisted
 *     `nextRunEpochMs` is in the past.
 *  2. For each, acquires the per-task `Mutex` ([IOSTaskMutexes]) — different
 *     ids run concurrently; same id is serialized.
 *  3. **Re-checks** `nextRunEpochMs <= now()` *inside* the mutex. If the
 *     other feed already ran this cycle, the value is now in the future and
 *     the call bails. This is the foreground/background race-coalescing point.
 *  4. **Atomically advances** `nextRunEpochMs += intervalMs` *before*
 *     invoking the worker. Any concurrent caller that arrives after this
 *     advance sees the new value and skips. Means a worker that gets
 *     cancelled mid-execution doesn't re-fire on the very next tick — the
 *     advance sticks. Workers should be idempotent (already a library-wide
 *     contract); if they need finer-grained progress, they persist their own
 *     state.
 *  5. Invokes the worker and applies the [WorkResult]. On `Retry`, the
 *     advance from step 4 is *overridden* by the backoff-computed next-run.
 *
 * **Multi-task semantics.** Workers launch concurrently on the supplied
 * [CoroutineScope] (parallel-best-effort). Whichever workers finish before
 * the scope is cancelled complete normally; cancellation by the OS expiration
 * handler (background feed) translates to `CancellationException` inside the
 * worker, which the dispatcher treats the same as `Retry` (attempt
 * incrementing toward the backoff cap).
 *
 * No platform-framework dependencies — pure logic on top of
 * [IOSStateStore]/[IOSTaskMutexes]/[WorkerRegistry] so the contract is
 * straightforward to unit-test against `MapSettings`.
 */
internal class IOSPeriodicDispatcher(
    private val state: IOSStateStore,
    private val mutexes: IOSTaskMutexes,
    private val registry: WorkerRegistry,
    @Suppress("unused") // reserved for ephemeral cleanup on terminal failure; not used in v1
    private val ephemeral: EphemeralRegistry,
    private val emitter: MonitorEventEmitter,
    private val gate: ReachabilityGate,
    // Injectable so tests can drive virtual time deterministically.
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val log = Logger.withTag("Backgrounder/iOS/Dispatcher")

    /**
     * Earliest [IOSStateStore.readNextRunEpochMs] across all active periodic
     * task ids, or `null` if no active periodic exists. Used by the feeds to
     * decide *when* to dispatch — the foreground loop sleeps until this
     * value, and the background feed's resubmission sets `earliestBeginDate`
     * to it.
     */
    fun soonestUpcomingNextRun(): Long? =
        state
            .knownTaskIds()
            .asSequence()
            .filter { state.readKind(it) == IOSStateStore.Kind.Periodic && state.readActive(it) }
            .mapNotNull { state.readNextRunEpochMs(it) }
            .minOrNull()

    /**
     * Run every active periodic whose `nextRunEpochMs <= now()` in parallel.
     * Suspends until all due workers finish or [scope] is cancelled.
     *
     * The supplied [scope] determines the dispatch context. The foreground
     * feed passes a long-lived in-process scope; the background feed passes
     * a scope wired to `BGTask.expirationHandler` so workers get cancelled
     * cleanly when iOS reclaims the budget.
     *
     * [capabilities] is forwarded into each [WorkerContext]. Foreground
     * passes a generous budget (in-process — no OS limit); background passes
     * the ~30-second App Refresh budget.
     */
    suspend fun dispatchDueWork(
        scope: CoroutineScope,
        capabilities: PlatformCapabilities,
    ) {
        val now = clock()
        val due =
            state
                .knownTaskIds()
                .filter { id ->
                    state.readKind(id) == IOSStateStore.Kind.Periodic &&
                        state.readActive(id) &&
                        (state.readNextRunEpochMs(id) ?: Long.MAX_VALUE) <= now
                }
        if (due.isEmpty()) {
            log.d { "dispatchDueWork: nothing due (now=$now)" }
            return
        }
        log.d { "dispatchDueWork: ${due.size} due (now=$now): ${due.joinToString { it.value }}" }
        // Launch each worker on the supplied scope (so cancellation
        // propagates from the foreground feed's lifecycle scope or the
        // background feed's BGTask-expiration-bound scope), then joinAll on
        // the resulting Jobs so this suspend returns when every worker has
        // observed completion or cancellation. Don't use `coroutineScope { }`
        // with `scope.launch { }` inside — coroutineScope only joins jobs
        // launched on its own scope, not on a parent.
        val jobs = due.map { id -> scope.launch { runOne(id, capabilities) } }
        jobs.joinAll()
    }

    private suspend fun runOne(
        taskId: TaskId,
        capabilities: PlatformCapabilities,
    ) {
        mutexes.withMutex(taskId) {
            val now = clock()
            val nextRun = state.readNextRunEpochMs(taskId)
            if (nextRun == null || nextRun > now) {
                // Coalescing point: the other feed advanced this id's cycle
                // while we were waiting on the mutex. Bail silently.
                log.d { "$taskId: skipped (already advanced; nextRun=$nextRun, now=$now)" }
                return@withMutex
            }
            val intervalMs =
                state.readIntervalMs(taskId)
                    ?: run {
                        log.e { "$taskId periodic with no interval_ms; skipping" }
                        return@withMutex
                    }
            val attempt = state.readAttempt(taskId)
            val input = state.readInput(taskId)
            val networkRequired = state.readNetworkRequired(taskId)

            // Atomic advance BEFORE worker invocation. This is the
            // load-bearing line for race-coalescing: any concurrent caller
            // arriving after this point sees the new value and skips. If the
            // worker returns Retry, we override below.
            state.setNextRunEpochMs(taskId, now + intervalMs)

            val startedAt = Clock.System.now()
            emitter.emit(
                MonitorEvent.WorkStarted(
                    taskId = taskId,
                    at = startedAt,
                    attempt = attempt,
                    expectedAt = kotlin.time.Instant.fromEpochMilliseconds(now),
                ),
            )
            val ctx =
                WorkerContext(
                    taskId = taskId,
                    attempt = attempt,
                    input = input,
                    capabilities = capabilities,
                )

            // ── Reachability gate (review-loop round 2). Apple has no
            // OS-level constraint enforcement on the dispatch paths this
            // class drives (BGAppRefreshTaskRequest + in-process foreground
            // loop both ignore `requiresNetworkConnectivity`). Honour
            // `WorkConstraints.networkRequired` at the library level by passing
            // the RAW per-invocation budget; the gate owns the single
            // `min(5s, budget / 4)` quartering and reports the effective wait
            // it used (see B-028), short-circuiting to Retry on timeout. The
            // cancellation / worker-throw branches below catch the same shape.
            val gateResult = gate.awaitReachable(networkRequired, capabilities.maxExecutionTime)

            val result: WorkResult =
                if (gateResult is ReachabilityGate.GateResult.TimedOut) {
                    log.i {
                        "$taskId reachability gate timed out " +
                            "(requirement=$networkRequired, waited=${gateResult.waited}); " +
                            "skipping worker, deferring as Retry"
                    }
                    emitter.emit(
                        MonitorEvent.AttemptDeferred(
                            taskId = taskId,
                            at = Clock.System.now(),
                            attempt = attempt,
                            reason =
                                DeferralReason.ReachabilityTimeout(
                                    requirement = networkRequired,
                                    waited = gateResult.waited,
                                ),
                        ),
                    )
                    WorkResult.Retry
                } else {
                    try {
                        val worker =
                            try {
                                registry.create(taskId)
                            } catch (e: WorkerRegistry.NoFactoryRegisteredException) {
                                emitter.emit(
                                    MonitorEvent.Skipped(
                                        taskId = taskId,
                                        at = Clock.System.now(),
                                        reason = SkipReason.NotRegistered,
                                    ),
                                )
                                throw e
                            } catch (t: Throwable) {
                                emitter.emit(
                                    MonitorEvent.AttemptFailed(
                                        taskId = taskId,
                                        at = Clock.System.now(),
                                        attempt = attempt,
                                        reason = AttemptFailureReason.FactoryThrew(t),
                                    ),
                                )
                                throw t
                            }
                        worker.execute(ctx)
                    } catch (e: CancellationException) {
                        // Treat OS cancellation (BGTask expiration) the same as
                        // Retry — workers might have made partial progress; the
                        // attempt counter inches toward the backoff cap, the
                        // already-advanced nextRunEpochMs gets overridden below.
                        log.i { "$taskId cancelled (likely BGTask expiration): ${e.message}" }
                        emitter.emit(
                            MonitorEvent.AttemptFailed(
                                taskId = taskId,
                                at = Clock.System.now(),
                                attempt = attempt,
                                reason = AttemptFailureReason.ExpiredByOS,
                            ),
                        )
                        WorkResult.Retry
                    } catch (t: Throwable) {
                        log.e(t) { "$taskId execute() threw; treating as Retry" }
                        emitter.emit(
                            MonitorEvent.AttemptFailed(
                                taskId = taskId,
                                at = Clock.System.now(),
                                attempt = attempt,
                                reason = AttemptFailureReason.WorkerThrew(t),
                            ),
                        )
                        WorkResult.Retry
                    }
                }

            applyResult(taskId, attempt, result, intervalMs, now)
            val completedAt = Clock.System.now()
            emitter.emit(
                MonitorEvent.WorkCompleted(
                    taskId = taskId,
                    at = completedAt,
                    attempt = attempt,
                    result = result,
                    runtime = completedAt - startedAt,
                ),
            )
            // If we just scheduled a retry inside applyResult, emit RetryScheduled
            // *after* WorkCompleted so observers see the natural order: the
            // attempt finishes, then the library queues the next attempt.
            if (result is WorkResult.Retry) {
                val backoff = backoffPolicyForRetry(taskId)
                val nextAttempt = attempt + 1
                if (!IOSBackoffEmulation.shouldGiveUp(backoff, nextAttempt)) {
                    val nextRunMs = state.readNextRunEpochMs(taskId)
                    emitter.emit(
                        MonitorEvent.RetryScheduled(
                            taskId = taskId,
                            at = completedAt,
                            nextAttempt = nextAttempt,
                            delay = backoff.delayFor(attempt),
                            nextRunHint = nextRunMs?.let { kotlin.time.Instant.fromEpochMilliseconds(it) },
                        ),
                    )
                }
            }
        }
    }

    private fun applyResult(
        taskId: TaskId,
        attempt: Int,
        result: WorkResult,
        intervalMs: Long,
        now: Long,
    ) {
        state.recordRun(taskId, result, nowMs = clock())
        when (result) {
            WorkResult.Success, is WorkResult.Failure -> {
                // Success/Failure: regular cycle continues. The advance in
                // runOne() already set nextRunEpochMs = now + intervalMs;
                // no further write needed.
                state.setAttempt(taskId, 0)
            }

            WorkResult.Retry -> {
                val backoff = backoffPolicyForRetry(taskId)
                val nextAttempt = attempt + 1
                if (IOSBackoffEmulation.shouldGiveUp(backoff, nextAttempt)) {
                    log.w {
                        "$taskId periodic exhausted maxAttempts(${backoff.maxAttempts}); " +
                            "treating as Failure and resuming regular cadence"
                    }
                    state.setAttempt(taskId, 0)
                    // Keep the regular-cycle advance from runOne().
                    return
                }
                state.setAttempt(taskId, nextAttempt)
                // Override the cycle advance with the backoff-computed
                // earlier wakeup. delayFor(attempt) — the attempt that *just
                // failed* — matches Android WorkManager's curve and the
                // existing one-shot retry path in BGTaskBackedScheduler.
                //
                // We compute the absolute timestamp from our injected `clock`
                // (via the `now` already captured at runOne entry).
                // IOSBackoffEmulation.nextRunEpochMs is now pure (takes nowMs;
                // no wall-clock read — N-011), so it would also be correct
                // here; we keep the local computation since `now` is already
                // in hand.
                val backoffDelayMs = backoff.delayFor(attempt).inWholeMilliseconds
                val backoffNextRun = now + backoffDelayMs
                // Don't override the regular cycle if the backoff would push
                // us past the next regular run — clamp to the regular run.
                // This keeps cadence honest when backoff (capped at 5h) is
                // longer than a short interval.
                val clamped = minOf(backoffNextRun, now + intervalMs)
                state.setNextRunEpochMs(taskId, clamped)
            }
        }
    }

    /**
     * Default backoff policy when a periodic returns [WorkResult.Retry].
     * `WorkRequest.Periodic` doesn't carry a [BackoffPolicy] field today
     * (only `OneTime` does), so we use exponential defaults. Mirrors the
     * existing one-shot fallback in `BGTaskBackedScheduler.backoffPolicyForRetry`.
     */
    private fun backoffPolicyForRetry(
        @Suppress("unused", "UNUSED_PARAMETER") taskId: TaskId,
    ): BackoffPolicy = BackoffPolicy.exponential()
}
