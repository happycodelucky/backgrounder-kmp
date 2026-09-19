package com.happycodelucky.backgrounder.jvm

import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.AttemptFailureReason
import com.happycodelucky.backgrounder.CancelOutcome
import com.happycodelucky.backgrounder.CancelSource
import com.happycodelucky.backgrounder.ConflictPolicy
import com.happycodelucky.backgrounder.DeferralReason
import com.happycodelucky.backgrounder.EphemeralRegistry
import com.happycodelucky.backgrounder.MonitorEvent
import com.happycodelucky.backgrounder.MonitorEventEmitter
import com.happycodelucky.backgrounder.PendingPredicate
import com.happycodelucky.backgrounder.PlatformCapabilities
import com.happycodelucky.backgrounder.ReachabilityGate
import com.happycodelucky.backgrounder.ScheduleOutcome
import com.happycodelucky.backgrounder.ScheduledTask
import com.happycodelucky.backgrounder.Scheduler
import com.happycodelucky.backgrounder.SchedulerGuarantees
import com.happycodelucky.backgrounder.SkipReason
import com.happycodelucky.backgrounder.TaskId
import com.happycodelucky.backgrounder.WorkRequest
import com.happycodelucky.backgrounder.WorkResult
import com.happycodelucky.backgrounder.WorkerContext
import com.happycodelucky.backgrounder.WorkerRegistry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * JVM [Scheduler] backed entirely by library-owned coroutines.
 *
 * The JVM (desktop / server) has no OS background-work primitive at all — no
 * `WorkManager`, no `BGTaskScheduler`, no `NSBackgroundActivityScheduler` — so
 * unlike the other platforms there is nothing to delegate to. Each scheduled
 * request becomes one [Job] on a `SupervisorJob`-rooted scope: one-shots
 * `delay(initialDelay)` then run (retrying per the request's `BackoffPolicy`),
 * periodics loop `delay(interval)` → run.
 *
 * Semantics mirror macOS's `NSBackgroundActivityBackedScheduler` — the other
 * in-process scheduler — with the differences the JVM's full control makes
 * possible:
 *  - `WorkStarted.expectedAt` and `ScheduledTask.nextRunHint` carry real
 *    values (we *are* the scheduler, so the planned fire time is known).
 *  - A one-shot that reaches a terminal result (Success, Failure, max-attempts
 *    give-up, structural skip) removes its own tracking, so [scheduled]
 *    reports only live work and a later `cancel` honestly returns `NoSuchTask`.
 *  - `WorkRequest.Periodic.flexWindow` is ignored — there is no OS to coalesce
 *    with, so tasks fire at the interval boundary.
 *  - `WorkConstraints.requiresCharging` is not enforced (no portable JVM power
 *    API) — same caveat as macOS; workers should check inside `execute()`.
 *  - `WorkConstraints.requiresDeviceIdle` is not enforced (no JVM idle primitive)
 *    — same caveat as macOS.
 *
 * Like macOS, nothing here survives the process: see [JVM_GUARANTEES] and the
 * process-death contract (LESSONS.md D-020 / D-022).
 *
 * @param dispatcher the dispatcher backing the scheduler's scope. Production
 *   uses [Dispatchers.Default]; tests inject a `TestDispatcher` so `delay`s
 *   run on virtual time.
 * @param clock injected wall-clock for event timestamps and run-time hints —
 *   never read `Clock.System` from dispatch math directly (LESSONS.md N-011).
 */
internal class CoroutineBackedScheduler(
    private val registry: WorkerRegistry,
    private val ephemeral: EphemeralRegistry,
    private val emitter: MonitorEventEmitter,
    private val gate: ReachabilityGate,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: Clock = Clock.System,
) : Scheduler {
    private val log = Logger.withTag("Backgrounder/JVM/Scheduler")

    private val scope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineName("Backgrounder.JVM"),
        )

    /**
     * Live registry keyed by task id. Owned by this scheduler instance.
     * MUST NOT call suspend functions inside `synchronized(lock) { ... }` blocks
     * — the `Scheduler` interface methods are non-suspending by design (see
     * CLAUDE.md §3 and Scheduler KDoc).
     */
    private val lock = SynchronizedObject()
    private val jobs: MutableMap<TaskId, Job> = mutableMapOf()
    private val attempts: MutableMap<TaskId, Int> = mutableMapOf()
    private val kinds: MutableMap<TaskId, ScheduledTask.Kind> = mutableMapOf()

    /**
     * The next planned fire per task id, in epoch milliseconds. Source for
     * `WorkStarted.expectedAt`, `ScheduledTask.nextRunHint`, and the backoff
     * predicate's `until`. Always computed from the injected [clock] (N-011).
     */
    private val nextRunEpochMs: MutableMap<TaskId, Long> = mutableMapOf()

    override fun schedule(
        request: WorkRequest,
        policy: ConflictPolicy,
    ): ScheduleOutcome {
        // H-3 (parallel to iOS / macOS, B-011): a Keep-policy no-op must NOT run
        // side effects. Fast-path probe so the common Keep case returns before
        // building a job; the authoritative decision is re-made under the lock.
        val keepWonRace =
            synchronized(lock) {
                policy == ConflictPolicy.Keep && jobs[request.taskId] != null
            }
        if (keepWonRace) {
            log.d { "schedule(${request.taskId}) Keep: existing job; not replacing" }
            return ScheduleOutcome.Scheduled
        }

        // Single authoritative critical section (B-022): decide, mutate the
        // maps, and record what happened. Events are emitted *after* the lock,
        // derived from the decision — never speculatively before it. The job is
        // created `LAZY` so nothing dispatches while the lock is held (N-013's
        // spirit applied to coroutine machinery); `start()` happens after
        // release. `ephemeral` mutation stays inside the lock, mirroring
        // cancel() / cancelAll() (consistent lock order: scheduler lock →
        // ephemeral lock).
        var replaced = false
        var displaced: Job? = null
        val job: Job? =
            synchronized(lock) {
                val existing = jobs[request.taskId]
                if (existing != null && policy == ConflictPolicy.Keep) {
                    log.d { "schedule(${request.taskId}) Keep: existing job; not replacing (lock-recheck)" }
                    return@synchronized null
                }
                displaced = existing
                replaced = existing != null

                val fresh =
                    scope.launch(start = CoroutineStart.LAZY) {
                        when (request) {
                            is WorkRequest.OneTime -> runOneTime(request)
                            is WorkRequest.Periodic -> runPeriodic(request)
                        }
                    }
                val firstDelay =
                    when (request) {
                        is WorkRequest.OneTime -> request.initialDelay
                        is WorkRequest.Periodic -> request.interval
                    }
                jobs[request.taskId] = fresh
                attempts[request.taskId] = 0
                kinds[request.taskId] =
                    when (request) {
                        is WorkRequest.OneTime -> ScheduledTask.Kind.OneTime
                        is WorkRequest.Periodic -> ScheduledTask.Kind.Periodic
                    }
                nextRunEpochMs[request.taskId] =
                    clock.now().toEpochMilliseconds() + firstDelay.inWholeMilliseconds
                if (request.ephemeral) ephemeral.add(request.taskId)
                fresh
            }
        if (job == null) return ScheduleOutcome.Scheduled

        // Cancel the displaced job OUTSIDE the lock — kotlinx-coroutines may run
        // completion handlers inline from cancel(). The displaced job's own
        // terminal cleanup is identity-guarded (see clearTracking), so it cannot
        // wipe the fresh entry just installed above.
        displaced?.cancel(CancellationException("replaced by newer schedule(${request.taskId})"))

        if (replaced) {
            emitter.emit(
                MonitorEvent.ScheduleReplaced(
                    taskId = request.taskId,
                    at = clock.now(),
                    policy = policy,
                    current = request,
                ),
            )
        }
        emitter.emit(
            MonitorEvent.Scheduled(
                taskId = request.taskId,
                at = clock.now(),
                request = request,
            ),
        )

        // A concurrent cancel()/Replace between the map insert above and this
        // call has already cancelled the lazy job, making start() a harmless
        // no-op — the same "invalidate first" pattern as macOS.
        job.start()
        return ScheduleOutcome.Scheduled
    }

    /**
     * One-shot lifecycle: initial delay, then attempt → backoff → retry until a
     * terminal result. Terminal paths remove this task's tracking (identity-
     * guarded so a Replace that displaced us mid-flight keeps its fresh entry).
     */
    private suspend fun runOneTime(request: WorkRequest.OneTime) {
        val self = coroutineContext.job
        delay(request.initialDelay)
        var attempt = 0
        while (true) {
            val expectedAtMs = synchronized(lock) { nextRunEpochMs[request.taskId] }
            val result = runAttempt(request, attempt, expectedAtMs)
            when (result) {
                // Structural skip (null), Success, or Failure — terminal.
                null, WorkResult.Success, is WorkResult.Failure -> {
                    clearTracking(request.taskId, self)
                    return
                }

                WorkResult.Retry -> {
                    val backoff = request.backoff
                    val nextAttempt = attempt + 1
                    if (nextAttempt >= backoff.maxAttempts) {
                        log.w {
                            "[${request.taskId}] one-shot reached maxAttempts(${backoff.maxAttempts}); " +
                                "converting Retry to Failure and dropping schedule"
                        }
                        clearTracking(request.taskId, self)
                        return
                    }

                    // delayFor(attempt) — the attempt that *just failed* — matches
                    // Android's WorkManager backoff curve and the iOS / macOS
                    // resubmit math (B-001).
                    val backoffDelay = backoff.delayFor(attempt)
                    val now = clock.now()
                    emitter.emit(
                        MonitorEvent.RetryScheduled(
                            taskId = request.taskId,
                            at = now,
                            nextAttempt = nextAttempt,
                            delay = backoffDelay,
                            nextRunHint = now + backoffDelay,
                        ),
                    )
                    synchronized(lock) {
                        attempts[request.taskId] = nextAttempt
                        nextRunEpochMs[request.taskId] =
                            now.toEpochMilliseconds() + backoffDelay.inWholeMilliseconds
                    }
                    delay(backoffDelay)
                    attempt = nextAttempt
                }
            }
        }
    }

    /**
     * Periodic lifecycle: fire once per [WorkRequest.Periodic.interval] until
     * cancelled. The first fire happens after one full interval — mirroring
     * macOS (`NSBackgroundActivityScheduler`) and Android (`PeriodicWorkRequest`).
     *
     * A cycle returning [WorkResult.Retry] increments the attempt counter and
     * waits for the next interval fire — the JVM translation of macOS's
     * `NSBackgroundActivityResultDeferred`. Success / Failure reset the counter
     * for the next cycle. Periodic requests carry no `BackoffPolicy`, so there
     * is no max-attempts give-up — again matching macOS. Missed cycles coalesce
     * into one fire on resume (D-005): a single `delay` per cycle, no catch-up.
     */
    private suspend fun runPeriodic(request: WorkRequest.Periodic) {
        while (true) {
            val plannedAtMs = clock.now().toEpochMilliseconds() + request.interval.inWholeMilliseconds
            synchronized(lock) { nextRunEpochMs[request.taskId] = plannedAtMs }
            delay(request.interval)

            val attempt = synchronized(lock) { attempts[request.taskId] ?: 0 }
            val result = runAttempt(request, attempt, plannedAtMs)
            synchronized(lock) {
                attempts[request.taskId] =
                    when (result) {
                        // Structural skip — the worker never ran; counter unchanged.
                        null -> attempt

                        WorkResult.Success, is WorkResult.Failure -> 0

                        WorkResult.Retry -> attempt + 1
                    }
            }
        }
    }

    /**
     * One dispatch: emit [MonitorEvent.WorkStarted], resolve the worker, gate
     * on reachability, execute, emit [MonitorEvent.WorkCompleted].
     *
     * @return the worker's [WorkResult] (the reachability-gate timeout maps to
     *   [WorkResult.Retry]), or `null` when no worker ran for a structural
     *   reason (no factory, factory declined, factory threw) — callers treat
     *   `null` as "this fire is over, no retry", mirroring macOS's
     *   `NSBackgroundActivityResultFinished` on those paths.
     */
    private suspend fun runAttempt(
        request: WorkRequest,
        attempt: Int,
        expectedAtMs: Long?,
    ): WorkResult? {
        val startedAt = clock.now()
        emitter.emit(
            MonitorEvent.WorkStarted(
                taskId = request.taskId,
                at = startedAt,
                attempt = attempt,
                expectedAt = expectedAtMs?.let { Instant.fromEpochMilliseconds(it) },
            ),
        )

        val worker =
            try {
                registry.create(request.taskId)
            } catch (e: WorkerRegistry.NoFactoryRegisteredException) {
                log.e(e) { "[${request.taskId}] no factory registered" }
                emitter.emit(
                    MonitorEvent.Skipped(
                        taskId = request.taskId,
                        at = clock.now(),
                        reason = SkipReason.NotRegistered,
                    ),
                )
                return null
            } catch (e: WorkerRegistry.FactoryDeclinedException) {
                log.e(e) { "[${request.taskId}] factory declined its declared id" }
                emitter.emit(
                    MonitorEvent.Skipped(
                        taskId = request.taskId,
                        at = clock.now(),
                        reason = SkipReason.FactoryDeclined,
                    ),
                )
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log.e(t) { "[${request.taskId}] factory threw" }
                emitter.emit(
                    MonitorEvent.AttemptFailed(
                        taskId = request.taskId,
                        at = clock.now(),
                        attempt = attempt,
                        reason = AttemptFailureReason.FactoryThrew(t),
                    ),
                )
                return null
            }

        val ctx =
            WorkerContext(
                taskId = request.taskId,
                attempt = attempt,
                input = request.input,
                capabilities = JVM_CAPABILITIES,
            )

        // ── Reachability gate (D-003). The JVM has no OS constraint concept,
        // so without this gate `WorkConstraints.networkRequired` would be
        // silently ignored — exactly the macOS situation. Pass the RAW
        // per-invocation budget; the gate owns the single `min(5s, budget/4)`
        // quartering and reports the effective wait it used (see B-028). With
        // the JVM's INFINITE budget that resolves to MAX_WAIT (5s). On timeout,
        // treat as `Retry` so the one-shot backoff loop / periodic next-cycle
        // handles rescheduling.
        val gateResult =
            gate.awaitReachable(request.constraints.networkRequired, ctx.capabilities.maxExecutionTime)
        val result =
            if (gateResult is ReachabilityGate.GateResult.TimedOut) {
                log.i {
                    "[${request.taskId}] reachability gate timed out " +
                        "(requirement=${request.constraints.networkRequired}, waited=${gateResult.waited}); " +
                        "skipping worker, deferring as Retry"
                }
                emitter.emit(
                    MonitorEvent.AttemptDeferred(
                        taskId = request.taskId,
                        at = clock.now(),
                        attempt = attempt,
                        reason =
                            DeferralReason.ReachabilityTimeout(
                                requirement = request.constraints.networkRequired,
                                waited = gateResult.waited,
                            ),
                    ),
                )
                WorkResult.Retry
            } else {
                try {
                    worker.execute(ctx)
                } catch (e: CancellationException) {
                    log.i { "[${request.taskId}] cancelled" }
                    throw e
                } catch (t: Throwable) {
                    log.e(t) { "[${request.taskId}] threw; treating as Retry" }
                    emitter.emit(
                        MonitorEvent.AttemptFailed(
                            taskId = request.taskId,
                            at = clock.now(),
                            attempt = attempt,
                            reason = AttemptFailureReason.WorkerThrew(t),
                        ),
                    )
                    WorkResult.Retry
                }
            }

        val completedAt = clock.now()
        emitter.emit(
            MonitorEvent.WorkCompleted(
                taskId = request.taskId,
                at = completedAt,
                attempt = attempt,
                result = result,
                runtime = completedAt - startedAt,
            ),
        )
        return result
    }

    /**
     * Remove every map entry (and the ephemeral marker) for [taskId] — but only
     * while [self] still owns the slot. A Replace that displaced this job
     * between its last suspension and this cleanup must keep the *fresh* job's
     * tracking intact (the same `removeIfSame` identity guard as
     * `PendingInstantCalls`).
     */
    private fun clearTracking(
        taskId: TaskId,
        self: Job,
    ) {
        val removed =
            synchronized(lock) {
                if (jobs[taskId] !== self) return@synchronized false
                jobs.remove(taskId)
                attempts.remove(taskId)
                kinds.remove(taskId)
                nextRunEpochMs.remove(taskId)
                ephemeral.remove(taskId)
                true
            }
        if (!removed) log.d { "[$taskId] terminal cleanup skipped — slot owned by a newer schedule" }
    }

    override fun cancel(taskId: TaskId): CancelOutcome {
        val cancelled: Job? =
            synchronized(lock) {
                val job = jobs.remove(taskId) ?: return@synchronized null
                attempts.remove(taskId)
                kinds.remove(taskId)
                nextRunEpochMs.remove(taskId)
                ephemeral.remove(taskId)
                job
            }
        if (cancelled == null) return CancelOutcome.NoSuchTask
        // Cancel outside the lock — kotlinx-coroutines may run completion
        // handlers inline from cancel() (N-013's spirit).
        cancelled.cancel(CancellationException("Backgrounder.cancel($taskId)"))
        emitter.emit(
            MonitorEvent.Cancelled(
                taskId = taskId,
                at = clock.now(),
                source = CancelSource.User,
            ),
        )
        return CancelOutcome.Cancelled(pendingCleared = 1)
    }

    override fun cancelAll(): CancelOutcome {
        val snapshot: List<Pair<TaskId, Job>> =
            synchronized(lock) {
                if (jobs.isEmpty()) return@synchronized emptyList()
                val snap = jobs.entries.map { it.key to it.value }
                jobs.clear()
                attempts.clear()
                kinds.clear()
                nextRunEpochMs.clear()
                ephemeral.clear()
                snap
            }
        if (snapshot.isEmpty()) return CancelOutcome.NoSuchTask
        snapshot.forEach { (_, job) -> job.cancel(CancellationException("Backgrounder.cancelAll()")) }
        val now = clock.now()
        snapshot.forEach { (id, _) ->
            emitter.emit(MonitorEvent.Cancelled(taskId = id, at = now, source = CancelSource.User))
        }
        return CancelOutcome.Cancelled(pendingCleared = snapshot.size)
    }

    override suspend fun scheduled(): List<ScheduledTask> =
        synchronized(lock) {
            // EphemeralRegistry takes its own lock inside ours — consistent
            // lock order (scheduler lock → ephemeral lock), same as schedule()
            // and cancel(). Non-suspending, so safe in this block.
            val ephemeralIds = ephemeral.snapshot()
            jobs.keys.map { id ->
                val attempt = attempts[id] ?: 0
                val state = if (attempt > 0) ScheduledTask.State.Backoff else ScheduledTask.State.Pending
                val hint = nextRunEpochMs[id]?.let { Instant.fromEpochMilliseconds(it) }
                ScheduledTask(
                    taskId = id,
                    kind = kinds[id] ?: ScheduledTask.Kind.OneTime,
                    state = state,
                    nextRunHint = hint,
                    attempt = attempt,
                    ephemeral = id in ephemeralIds,
                    pendingPredicates =
                        if (state == ScheduledTask.State.Backoff) {
                            listOf(PendingPredicate.WaitingForBackoff(until = hint))
                        } else {
                            emptyList()
                        },
                )
            }
        }

    override fun guarantees(): SchedulerGuarantees = JVM_GUARANTEES

    /** Cancel everything and stop the scope. Called by Backgrounder.shutdown via the JVM builder. */
    fun shutdown() {
        cancelAll()
        scope.cancel()
    }

    private companion object {
        /**
         * The JVM process is fully ours — no OS scheduler grants or reclaims
         * background time, so there is no execution budget to report. The
         * reachability gate receives this raw `INFINITE` budget and clamps its
         * own wait at the 5-second cap (`min(MAX_WAIT, INFINITE / 4)` picks
         * `MAX_WAIT`).
         */
        private val JVM_CAPABILITIES =
            PlatformCapabilities(
                maxExecutionTime = Duration.INFINITE,
                cancelsInFlight = true,
            )
    }
}

private val JVM_GUARANTEES =
    SchedulerGuarantees(
        // In-process coroutines: scheduled jobs die with the process — nothing
        // survives quit, force-quit (SIGKILL), or reboot, and nothing
        // relaunches the app. Same durability story as macOS's
        // NSBackgroundActivityScheduler; never misadvertise durability (B-027).
        // Apps re-schedule from their own init path at next launch; the
        // process-death contract (D-020 / D-022) means the library never
        // replays a run that died with its process.
        survivesProcessDeath = false,
        survivesReboot = false,
        survivesForceQuit = false,
        honoursWallClock = true, // approximately — delay() is monotonic; deep sleep may stretch alignment.
        supportsRetryBackoff = true,
        cancelsInFlight = true,
        // The coroutine loop has no technical floor; 1 second is a sanity
        // floor mirroring macOS. (WorkRequest.Periodic itself enforces the
        // 15-minute cross-platform recommendation at construction.)
        minimumPeriodicInterval = 1.seconds,
        maxConcurrentTasks = null,
    )
