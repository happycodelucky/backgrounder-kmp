// ExperimentalForeignApi: required for cinterop FFI types (NSDate, BGTask, etc.).
// The cinterop surface has been stable across multiple Kotlin releases.
@file:OptIn(ExperimentalForeignApi::class)

package com.happycodelucky.backgrounder.ios

import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.BackoffPolicy
import com.happycodelucky.backgrounder.CancelOutcome
import com.happycodelucky.backgrounder.CancelSource
import com.happycodelucky.backgrounder.CompletionGuard
import com.happycodelucky.backgrounder.ConflictPolicy
import com.happycodelucky.backgrounder.EphemeralRegistry
import com.happycodelucky.backgrounder.ExecutionHint
import com.happycodelucky.backgrounder.MonitorEvent
import com.happycodelucky.backgrounder.MonitorEventEmitter
import com.happycodelucky.backgrounder.ScheduleOutcome
import com.happycodelucky.backgrounder.ScheduledTask
import com.happycodelucky.backgrounder.Scheduler
import com.happycodelucky.backgrounder.SchedulerGuarantees
import com.happycodelucky.backgrounder.WorkRequest
import com.happycodelucky.backgrounder.WorkResult
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import kotlin.time.Clock

/**
 * iOS [Scheduler] backed by `BGTaskScheduler`.
 *
 * Periodic semantics are library-emulated — see plan §"iOS implementation"
 * step 2 (state machine). Retry/backoff is also emulated via [IOSStateStore]
 * and [IOSBackoffEmulation].
 */
internal class BGTaskBackedScheduler(
    private val state: IOSStateStore,
    private val mutexes: IOSTaskMutexes,
    private val ephemeral: EphemeralRegistry,
    private val emitter: MonitorEventEmitter,
    // Step 6 cut-over: Periodics no longer have per-task id BGTaskRequests.
    // Instead, scheduling a Periodic writes to state and signals both feeds:
    //  - backgroundFeed.submitNextTick() so iOS's tick request gets refreshed
    //    if this Periodic is now the soonest upcoming nextRunEpochMs;
    //  - foregroundFeed.kick() so the in-process loop re-evaluates its delay.
    // OneShot scheduling is unchanged — still per-task id BGTaskRequests.
    private val backgroundFeed: IOSBackgroundFeed,
    private val foregroundFeed: IOSForegroundFeed,
    // Injectable so tests can simulate BGTaskScheduler.submit failures —
    // the real scheduler needs a live app host. Production uses the default.
    private val submitRequest: (BGTaskRequest) -> BGSubmitResult = ::submitBGTaskRequest,
    // Injectable so tests can drive virtual time (N-011 / B-020). All schedule
    // and retry timestamp math goes through this; production reads wall-clock.
    // Event `at` timestamps and the NSDate FFI conversion stay wall-clock.
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : Scheduler {
    private val log = Logger.withTag("Backgrounder/iOS/Scheduler")

    /**
     * Used by the handler bridge after a worker returns. Wired through the Koin module.
     *
     * The [guard] is created per BGTask invocation by [IOSCoroutineBridge] and threaded
     * through every code path that can call `setTaskCompletedWithSuccess`. Apple raises
     * a fatal assertion when that method is called twice on the same `BGTask`; the
     * guard makes the second-and-later attempts no-ops. This protects against:
     *
     *  - the worker completing successfully and `applyResult` calling completion,
     *    while a near-simultaneous BGTask expiration races into the
     *    `invokeOnCompletion` fallback in [IOSCoroutineBridge];
     *  - the cancel-won-the-race path (active=false) where we still need to tell
     *    iOS the task finished, even though we didn't reschedule.
     */
    internal suspend fun applyResult(
        task: BGTask,
        taskId: String,
        attempt: Int,
        result: WorkResult,
        guard: CompletionGuard,
    ) {
        mutexes.withMutex(taskId) {
            state.recordRun(taskId, result, nowMs = clock())
            val active = state.readActive(taskId)

            when (state.readKind(taskId)) {
                IOSStateStore.Kind.OneShot -> {
                    handleOneShotResult(task, taskId, attempt, result, active, guard)
                }

                IOSStateStore.Kind.Periodic -> {
                    // Defensive: post step-6 cut-over, periodics flow through
                    // IOSPeriodicDispatcher (driven by the foreground/background
                    // feeds). Reaching this branch means a per-task id
                    // BGTaskScheduler launch handler was registered for a
                    // periodic id (which still happens — registerOne covers all
                    // factory ids defensively) AND iOS dispatched it (which
                    // shouldn't happen — nothing submits per-id requests for
                    // periodics anymore). Treat as unknown and complete the
                    // BGTask so iOS doesn't reclaim the process.
                    log.w {
                        "$taskId: applyResult reached for Periodic via per-id BGTask path. " +
                            "Periodics should flow through IOSPeriodicDispatcher; " +
                            "completing BGTask defensively."
                    }
                    guard.runOnce { task.setTaskCompletedWithSuccess(result is WorkResult.Success) }
                }

                null -> {
                    log.w { "applyResult for unknown task id $taskId; marking iOS task complete" }
                    mutexes.forget(taskId)
                    guard.runOnce { task.setTaskCompletedWithSuccess(result is WorkResult.Success) }
                }
            }
        }
    }

    private fun handleOneShotResult(
        task: BGTask,
        taskId: String,
        attempt: Int,
        result: WorkResult,
        active: Boolean,
        guard: CompletionGuard,
    ) {
        when (result) {
            WorkResult.Success, is WorkResult.Failure -> {
                state.setActive(taskId, false)
                state.clear(taskId)
                ephemeral.remove(taskId)
                mutexes.forget(taskId)
                guard.runOnce { task.setTaskCompletedWithSuccess(result is WorkResult.Success) }
            }

            WorkResult.Retry -> {
                if (!active) {
                    log.i { "$taskId one-shot Retry but active=false (cancel won the race); not resubmitting" }
                    guard.runOnce { task.setTaskCompletedWithSuccess(true) }
                    return
                }
                val backoff = backoffPolicyForRetry(taskId)
                val nextAttempt = attempt + 1
                if (IOSBackoffEmulation.shouldGiveUp(backoff, nextAttempt)) {
                    log.w { "$taskId reached maxAttempts(${backoff.maxAttempts}); converting to Failure" }
                    state.setActive(taskId, false)
                    state.clear(taskId)
                    ephemeral.remove(taskId)
                    mutexes.forget(taskId)
                    guard.runOnce { task.setTaskCompletedWithSuccess(false) }
                    return
                }
                state.setAttempt(taskId, nextAttempt)
                // delayFor(attempt) — the attempt that *just failed* — matches Android's
                // WorkManager backoff curve: first retry waits `initialDelay`, second waits
                // `2 * initialDelay` (exponential) or `2 * initialDelay` (linear), etc.
                val nextRun = IOSBackoffEmulation.nextRunEpochMs(backoff, attempt, clock())
                state.setNextRunEpochMs(taskId, nextRun)
                emitter.emit(
                    MonitorEvent.RetryScheduled(
                        taskId = taskId,
                        at =
                            kotlin.time.Clock.System
                                .now(),
                        nextAttempt = nextAttempt,
                        delay = backoff.delayFor(attempt),
                        nextRunHint = kotlin.time.Instant.fromEpochMilliseconds(nextRun),
                    ),
                )
                resubmit(taskId, nextRun)
                guard.runOnce { task.setTaskCompletedWithSuccess(true) }
            }
        }
    }

    /**
     * The resubmit path for one-shot Retry handling. Periodics never call
     * this post step-6 cut-over — they flow through [IOSPeriodicDispatcher],
     * which has its own retry/backoff machinery. The one-shot
     * [WorkRequest.OneTime.executionHint] isn't preserved across resubmit
     * (we reconstruct from kind alone, defaulting to `BGProcessingTaskRequest`);
     * Expedited one-shots that hit Retry will degrade to Standard on retry.
     * Documented as a v1 limitation.
     */
    private fun resubmit(
        taskId: String,
        earliestEpochMs: Long,
    ) {
        val request =
            BGProcessingTaskRequest(taskId).apply {
                earliestBeginDate = epochMsToNSDate(earliestEpochMs)
            }
        when (val outcome = submitRequest(request)) {
            BGSubmitResult.Success -> Unit
            is BGSubmitResult.Failure -> log.e { "[$taskId] resubmit failed: ${outcome.message}" }
        }
    }

    /**
     * Default backoff policy for retries when we can't recover the original.
     * The user picked it at schedule() time; we don't persist it (yet) so the
     * v1 implementation falls back to a sensible exponential default.
     *
     * v2: persist [BackoffPolicy] alongside the rest of the state.
     */
    private fun backoffPolicyForRetry(taskId: String): BackoffPolicy = BackoffPolicy.exponential()

    // --- Scheduler interface ------------------------------------------------

    override fun schedule(
        request: WorkRequest,
        policy: ConflictPolicy,
    ): ScheduleOutcome {
        // H-3 (review-loop round 1): Keep-policy early-return must NOT run side
        // effects. Previously `ephemeral.add(...)` and the `onScheduled` emission
        // both fired before the Keep guard, so a no-op schedule looked to metrics
        // like a real schedule and could mutate the ephemeral registry of an
        // already-active non-ephemeral task. Check the guard first.
        if (policy == ConflictPolicy.Keep && state.readActive(request.taskId)) {
            log.d { "schedule(${request.taskId}) Keep: existing active; not resubmitting" }
            return ScheduleOutcome.Scheduled
        }

        // ScheduleReplaced fires *before* Scheduled when a Replace policy
        // displaces an existing active task. Observers see the prior task end
        // (logically) and the new one begin.
        if (policy == ConflictPolicy.Replace && state.readActive(request.taskId)) {
            emitter.emit(
                MonitorEvent.ScheduleReplaced(
                    taskId = request.taskId,
                    at =
                        kotlin.time.Clock.System
                            .now(),
                    policy = policy,
                    current = request,
                ),
            )
        }

        if (request.ephemeral) ephemeral.add(request.taskId)
        emitter.emit(
            MonitorEvent.Scheduled(
                taskId = request.taskId,
                at =
                    kotlin.time.Clock.System
                        .now(),
                request = request,
            ),
        )

        return when (request) {
            is WorkRequest.OneTime -> scheduleOneTime(request)
            is WorkRequest.Periodic -> schedulePeriodic(request)
        }
    }

    private fun scheduleOneTime(request: WorkRequest.OneTime): ScheduleOutcome {
        val nextRun = IOSBackoffEmulation.epochMillisAt(request.initialDelay, clock())
        state.writeOnSchedule(
            taskId = request.taskId,
            kind = IOSStateStore.Kind.OneShot,
            input = request.input,
            ephemeral = request.ephemeral,
            intervalMs = null,
            nextRunEpochMs = nextRun,
            networkRequired = request.constraints.networkRequired,
        )
        val osRequest =
            newOSRequest(request.taskId, request.executionHint).apply {
                earliestBeginDate = epochMsToNSDate(nextRun)
                applyConstraints(request.constraints, request.executionHint)
            }
        return submit(osRequest, request.taskId)
    }

    private fun schedulePeriodic(request: WorkRequest.Periodic): ScheduleOutcome {
        val nextRun = clock() + request.interval.inWholeMilliseconds
        state.writeOnSchedule(
            taskId = request.taskId,
            kind = IOSStateStore.Kind.Periodic,
            input = request.input,
            ephemeral = request.ephemeral,
            intervalMs = request.interval.inWholeMilliseconds,
            nextRunEpochMs = nextRun,
            networkRequired = request.constraints.networkRequired,
        )
        // Step 6 cut-over: no per-task id BGTaskRequest. The dispatcher decides
        // what runs at each tick; both feeds wake up to consult its
        // soonestUpcomingNextRun().
        //
        // Constraints (request.constraints) are intentionally NOT honored on
        // periodics post-cut-over: the background feed always uses
        // BGAppRefreshTaskRequest, which has no requiresExternalPower /
        // requiresNetworkConnectivity fields, and the foreground feed runs
        // in-process where iOS-style constraints don't apply. Workers that
        // need power/network gating should check at the start of execute()
        // and return WorkResult.Retry if the conditions aren't met (the
        // dispatcher's backoff logic will reschedule appropriately).
        backgroundFeed.submitNextTick()
        foregroundFeed.kick()
        return ScheduleOutcome.Scheduled
    }

    private fun newOSRequest(
        taskId: String,
        hint: ExecutionHint,
    ): BGTaskRequest =
        when (hint) {
            ExecutionHint.Standard -> BGProcessingTaskRequest(taskId)
            is ExecutionHint.Expedited -> BGAppRefreshTaskRequest(taskId)
        }

    private fun submit(
        request: BGTaskRequest,
        taskId: String,
    ): ScheduleOutcome =
        when (val outcome = submitRequest(request)) {
            BGSubmitResult.Success -> {
                ScheduleOutcome.Scheduled
            }

            is BGSubmitResult.Failure -> {
                log.e { "submit failed for $taskId: ${outcome.message}" }
                emitter.emit(
                    MonitorEvent.LibraryError(
                        taskId = taskId,
                        at =
                            kotlin.time.Clock.System
                                .now(),
                        message = "BGTaskScheduler.submit failed: ${outcome.message}",
                        cause = null,
                    ),
                )
                // Wipe state AND the ephemeral marker so we don't leave a
                // phantom record — schedule() added the ephemeral entry before
                // submission, and a failed submit must roll both back (B-023).
                state.clear(taskId)
                ephemeral.remove(taskId)
                ScheduleOutcome.Rejected("BGTaskScheduler.submit failed: ${outcome.message}")
            }
        }

    override fun cancel(taskId: String): CancelOutcome {
        // Capture kind BEFORE clear() — we need it to decide whether to cancel
        // a per-id BGTaskRequest (one-shots only) and whether to refresh the
        // background feed's tick (periodics only).
        val kindBeforeClear = state.readKind(taskId)
        val known = kindBeforeClear != null

        if (kindBeforeClear == IOSStateStore.Kind.OneShot) {
            // One-shots still have a per-task id BGTaskRequest pending in iOS.
            BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(taskId)
        }
        // Periodics have no per-id request post-cut-over — the tick handles all.

        // Same C-3 ordering as cancelAll: setActive(false) first so any
        // in-flight handler / dispatcher pass reads the cancelled state.
        state.setActive(taskId, false)
        state.clear(taskId)
        ephemeral.remove(taskId)
        mutexes.forget(taskId)
        emitter.emit(
            MonitorEvent.Cancelled(
                taskId = taskId,
                at =
                    kotlin.time.Clock.System
                        .now(),
                source = CancelSource.User,
            ),
        )

        if (kindBeforeClear == IOSStateStore.Kind.Periodic) {
            // Refresh the tick — soonest may have moved later (or to null) now
            // that this periodic is gone. Refresh the foreground loop too.
            backgroundFeed.submitNextTick()
            foregroundFeed.kick()
        }

        return if (known) CancelOutcome.Cancelled(pendingCleared = 1) else CancelOutcome.NoSuchTask
    }

    override fun cancelAll(): CancelOutcome {
        val ids = state.knownTaskIds()
        if (ids.isEmpty()) {
            // Nothing in our state, but defensively also cancel everything in
            // iOS's queue — including the library's tick request if anyone
            // pre-queued one. cancelAllTaskRequests is a single iOS call.
            BGTaskScheduler.sharedScheduler.cancelAllTaskRequests()
            return CancelOutcome.NoSuchTask
        }
        // C-3: mirror the single-task `cancel()` ordering. Set `active=false` BEFORE
        // clearing state so an in-flight handler / dispatcher pass that wakes up
        // between the iOS cancel call and `clear` reads `active=false` and takes
        // the "cancel won the race" path rather than reading still-true state and
        // resubmitting a task we just cancelled.
        //
        // Step 6 cut-over: for periodics, there's no per-id BGTaskRequest to cancel
        // in iOS's queue (only the tick exists, and we cancel it once after the
        // loop). For one-shots, the per-id cancel still applies.
        val cancelledAt =
            kotlin.time.Clock.System
                .now()
        ids.forEach { id ->
            if (state.readKind(id) == IOSStateStore.Kind.OneShot) {
                BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(id)
            }
            state.setActive(id, false)
            state.clear(id)
            emitter.emit(
                MonitorEvent.Cancelled(
                    taskId = id,
                    at = cancelledAt,
                    source = CancelSource.User,
                ),
            )
        }
        // Cancel the tick request — there are no periodics left to drive it.
        backgroundFeed.cancel()
        ephemeral.clear()
        mutexes.forgetAll()
        return CancelOutcome.Cancelled(pendingCleared = ids.size)
    }

    override suspend fun scheduled(): List<ScheduledTask> = IOSScheduledTaskQuery(state).snapshot()

    override fun guarantees(): SchedulerGuarantees = IOS_GUARANTEES

    private fun BGProcessingTaskRequest.applyConstraints(
        constraints: com.happycodelucky.backgrounder.WorkConstraints,
        hint: ExecutionHint,
    ) {
        // BGAppRefreshTaskRequest has no constraint fields; only BGProcessingTaskRequest does.
        // Caller already chose the right subclass via newOSRequest. This @apply method only
        // runs when we built a BGProcessingTaskRequest.
        when (constraints.networkRequired) {
            com.happycodelucky.backgrounder.NetworkRequirement.None -> {
                requiresNetworkConnectivity = false
            }

            com.happycodelucky.backgrounder.NetworkRequirement.Any -> {
                requiresNetworkConnectivity = true
            }

            com.happycodelucky.backgrounder.NetworkRequirement.Unmetered -> {
                requiresNetworkConnectivity = true
                log.w {
                    "NetworkRequirement.Unmetered is not honored on iOS; treating as Any. " +
                        "(hint=$hint)"
                }
            }
        }
        requiresExternalPower = constraints.requiresCharging
        // BGProcessingTaskRequest has no device-idle knob — the OS already prefers
        // idle moments when it fires background tasks. Log once so consumers know
        // the constraint was accepted but is not OS-enforced on iOS.
        if (constraints.requiresDeviceIdle) {
            log.w {
                "WorkConstraints.requiresDeviceIdle is not honored on iOS; ignored. " +
                    "BGProcessingTaskRequest has no device-idle field — the OS schedules " +
                    "background tasks at opportune (typically idle) moments regardless. " +
                    "(hint=$hint)"
            }
        }
    }

    /** Helper so BGAppRefreshTaskRequest can also call applyConstraints() — it's a no-op. */
    private fun BGAppRefreshTaskRequest.applyConstraints(
        @Suppress("UNUSED_PARAMETER") constraints: com.happycodelucky.backgrounder.WorkConstraints,
        @Suppress("UNUSED_PARAMETER") hint: ExecutionHint,
    ) {
        // BGAppRefreshTaskRequest doesn't expose constraints. Documented in plan.
        // requiresDeviceIdle is likewise unsupported on this request type.
    }

    private fun BGTaskRequest.applyConstraints(
        constraints: com.happycodelucky.backgrounder.WorkConstraints,
        hint: ExecutionHint,
    ) {
        when (this) {
            is BGProcessingTaskRequest -> applyConstraints(constraints, hint)
            is BGAppRefreshTaskRequest -> applyConstraints(constraints, hint)
        }
    }
}

/**
 * Convert epoch millis to an NSDate. Deliberately wall-clock (N-011 exemption):
 * this is the FFI boundary to an absolute-time OS API — `earliestBeginDate` is
 * only meaningful against the device's real clock. All *decision* math happens
 * upstream against the injected clock; this is presentation only.
 */
internal fun epochMsToNSDate(epochMs: Long): NSDate {
    val seconds = (epochMs - Clock.System.now().toEpochMilliseconds()) / 1000.0
    return NSDate.dateWithTimeIntervalSinceNow(seconds)
}

private val IOS_GUARANTEES =
    SchedulerGuarantees(
        survivesProcessDeath = true,
        survivesReboot = true,
        survivesForceQuit = false,
        honoursWallClock = false,
        supportsRetryBackoff = true,
        cancelsInFlight = false,
        minimumPeriodicInterval = WorkRequest.MIN_RECOMMENDED_INTERVAL,
        maxConcurrentTasks = 1000,
    )
