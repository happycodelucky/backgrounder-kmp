package com.happycodelucky.backgrounder.android

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.BackoffPolicy
import com.happycodelucky.backgrounder.CancelOutcome
import com.happycodelucky.backgrounder.CancelSource
import com.happycodelucky.backgrounder.ConflictPolicy
import com.happycodelucky.backgrounder.EphemeralRegistry
import com.happycodelucky.backgrounder.ExecutionHint
import com.happycodelucky.backgrounder.MonitorEvent
import com.happycodelucky.backgrounder.MonitorEventEmitter
import com.happycodelucky.backgrounder.QuotaPolicy
import com.happycodelucky.backgrounder.ScheduleOutcome
import com.happycodelucky.backgrounder.ScheduledTask
import com.happycodelucky.backgrounder.Scheduler
import com.happycodelucky.backgrounder.SchedulerGuarantees
import com.happycodelucky.backgrounder.WorkRequest
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration
import androidx.work.BackoffPolicy as AndroidBackoffPolicy
import androidx.work.WorkRequest as AndroidWorkRequest

/**
 * Android [Scheduler] backed by Jetpack `WorkManager`.
 *
 * One bridge worker class ([RegistryDispatchWorker]) handles every task id —
 * see plan §"Why one bridge worker for all task ids."
 */
internal class WorkManagerScheduler(
    private val workManagerProvider: () -> WorkManager,
    private val ephemeral: EphemeralRegistry,
    private val emitter: MonitorEventEmitter,
    private val scheduledTaskQuery: AndroidScheduledTaskQuery,
    /**
     * Process-local in-memory tracker of task ids this scheduler has enqueued.
     * Drives the [CancelOutcome] return value (review-loop round 1, finding
     * H-CONSENSUS-1). Defaulted so production wiring stays a no-arg
     * construction; tests can inject their own.
     */
    private val scheduledIds: ScheduledIdsTracker = ScheduledIdsTracker(),
) : Scheduler {
    /**
     * `WorkManager.getInstance(context)` is itself a fast singleton lookup once
     * the configuration is locked, so per-call invocation is cheap.
     */
    private val workManager: WorkManager get() = workManagerProvider()

    private val log = Logger.withTag("Backgrounder/WorkManagerScheduler")

    override fun schedule(
        request: WorkRequest,
        policy: ConflictPolicy,
    ): ScheduleOutcome {
        // ScheduleReplaced fires *before* Scheduled when Replace policy
        // displaces an existing tracked id. The was-tracked decision and the
        // add are ONE guarded operation — a snapshot-then-add pair would let
        // two racing schedule() calls emit inconsistent ScheduleReplaced
        // events. WorkManager's REPLACE / UPDATE semantics then take over
        // for the OS-side replacement.
        val wasTracked = scheduledIds.addAndWasPresent(request.taskId)
        if (policy == ConflictPolicy.Replace && wasTracked) {
            emitter.emit(
                MonitorEvent.ScheduleReplaced(
                    taskId = request.taskId,
                    at = Clock.System.now(),
                    policy = policy,
                    current = request,
                ),
            )
        }

        if (request.ephemeral) ephemeral.add(request.taskId)
        emitter.emit(
            MonitorEvent.Scheduled(
                taskId = request.taskId,
                at = Clock.System.now(),
                request = request,
            ),
        )

        return when (request) {
            is WorkRequest.OneTime -> scheduleOneTime(request, policy)
            is WorkRequest.Periodic -> schedulePeriodic(request, policy)
        }
    }

    private fun scheduleOneTime(
        request: WorkRequest.OneTime,
        policy: ConflictPolicy,
    ): ScheduleOutcome {
        val builder =
            OneTimeWorkRequest
                .Builder(RegistryDispatchWorker::class.java)
                .setInputData(
                    AndroidWorkInputMapper.toData(
                        taskId = request.taskId,
                        input = request.input,
                        ephemeral = request.ephemeral,
                        maxAttempts = request.backoff.maxAttempts,
                    ),
                ).setConstraints(request.constraints.toWorkManagerConstraints())
                .setInitialDelay(request.initialDelay.toMillis(), TimeUnit.MILLISECONDS)
                .applyBackoff(request.backoff)
                .applyExecutionHint(request.executionHint)
                .applyTags(request.taskId, periodic = false)

        val workRequest: AndroidWorkRequest = builder.build()
        workManager.enqueueUniqueWork(request.taskId, policy.toAndroidOneTimePolicy(), workRequest as OneTimeWorkRequest)
        return ScheduleOutcome.Scheduled
    }

    private fun schedulePeriodic(
        request: WorkRequest.Periodic,
        policy: ConflictPolicy,
    ): ScheduleOutcome {
        val intervalMs = request.interval.toMillis()
        val flexMs = request.flexWindow?.toMillis()

        val builder =
            if (flexMs != null) {
                PeriodicWorkRequest.Builder(
                    RegistryDispatchWorker::class.java,
                    intervalMs,
                    TimeUnit.MILLISECONDS,
                    flexMs,
                    TimeUnit.MILLISECONDS,
                )
            } else {
                PeriodicWorkRequest.Builder(
                    RegistryDispatchWorker::class.java,
                    intervalMs,
                    TimeUnit.MILLISECONDS,
                )
            }

        builder
            .setInputData(
                AndroidWorkInputMapper.toData(
                    taskId = request.taskId,
                    input = request.input,
                    ephemeral = request.ephemeral,
                    // Periodic work uses maxAttempts only as a per-cycle cap (plan §iOS,
                    // mirrored on Android via the same RegistryDispatchWorker logic).
                    maxAttempts = BackoffPolicy.DEFAULT_MAX_ATTEMPTS,
                ),
            ).setConstraints(request.constraints.toWorkManagerConstraints())
            .applyTags(request.taskId, periodic = true)

        workManager.enqueueUniquePeriodicWork(
            request.taskId,
            policy.toAndroidPeriodicPolicy(),
            builder.build(),
        )
        return ScheduleOutcome.Scheduled
    }

    override fun cancel(taskId: String): CancelOutcome {
        // Best-effort: if this process didn't schedule the id, return NoSuchTask
        // honestly so callers branching on the outcome see the same shape they
        // get on iOS / macOS (review-loop round 1, finding H-CONSENSUS-1).
        // `cancelUniqueWork` is still called regardless so cross-process work
        // gets cancelled on WorkManager's side.
        val wasKnown = scheduledIds.removeIfPresent(taskId)
        ephemeral.remove(taskId)
        workManager.cancelUniqueWork(taskId)
        if (wasKnown) {
            emitter.emit(
                MonitorEvent.Cancelled(
                    taskId = taskId,
                    at = Clock.System.now(),
                    source = CancelSource.User,
                ),
            )
            return CancelOutcome.Cancelled(pendingCleared = 1)
        }
        return CancelOutcome.NoSuchTask
    }

    override fun cancelAll(): CancelOutcome {
        val priorIds = scheduledIds.snapshot()
        val cleared = scheduledIds.clearAndCount()
        // Cancel all Backgrounder-tagged work — even cross-process — so the OS
        // side stays consistent regardless of whether we tracked the id locally.
        workManager.cancelAllWorkByTag(AndroidScheduledTaskMapper.BACKGROUNDER_TAG)
        ephemeral.clear()
        if (cleared == 0) {
            log.d { "cancelAll(): nothing scheduled in this process" }
            return CancelOutcome.NoSuchTask
        }
        val now = Clock.System.now()
        priorIds.forEach { id ->
            emitter.emit(MonitorEvent.Cancelled(taskId = id, at = now, source = CancelSource.User))
        }
        log.i { "cancelAll(): cleared $cleared Backgrounder-tagged work item(s)" }
        return CancelOutcome.Cancelled(pendingCleared = cleared)
    }

    override suspend fun scheduled(): List<ScheduledTask> = scheduledTaskQuery.snapshot()

    override fun guarantees(): SchedulerGuarantees = ANDROID_GUARANTEES

    private fun OneTimeWorkRequest.Builder.applyBackoff(policy: BackoffPolicy): OneTimeWorkRequest.Builder {
        val initialDelayMs = policy.delayFor(0).toMillis()
        val type =
            when (policy) {
                is BackoffPolicy.Linear -> AndroidBackoffPolicy.LINEAR
                is BackoffPolicy.Exponential -> AndroidBackoffPolicy.EXPONENTIAL
            }
        return setBackoffCriteria(type, initialDelayMs, TimeUnit.MILLISECONDS)
    }

    private fun OneTimeWorkRequest.Builder.applyExecutionHint(hint: ExecutionHint): OneTimeWorkRequest.Builder =
        when (hint) {
            ExecutionHint.Standard -> this
            is ExecutionHint.Expedited -> setExpedited(hint.onQuotaExhausted.toAndroid())
        }
}

private fun ConflictPolicy.toAndroidOneTimePolicy(): ExistingWorkPolicy =
    when (this) {
        ConflictPolicy.Replace -> ExistingWorkPolicy.REPLACE
        ConflictPolicy.Keep -> ExistingWorkPolicy.KEEP
    }

// Replace → UPDATE is deliberate, and semantically different from one-shot
// REPLACE: UPDATE patches the existing periodic request in place (interval,
// constraints, input) while PRESERVING WorkManager's run history and next-run
// anchor — the cadence does not reset. REPLACE would cancel-and-re-enqueue and
// restart the cycle from "now". Documented in docs/recipes/one-shot.md; the
// ScheduleReplaced event still fires, meaning "request contents replaced",
// not "schedule reset".
private fun ConflictPolicy.toAndroidPeriodicPolicy(): ExistingPeriodicWorkPolicy =
    when (this) {
        ConflictPolicy.Replace -> ExistingPeriodicWorkPolicy.UPDATE
        ConflictPolicy.Keep -> ExistingPeriodicWorkPolicy.KEEP
    }

private fun QuotaPolicy.toAndroid(): OutOfQuotaPolicy =
    when (this) {
        QuotaPolicy.RunAsRegular -> OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
        QuotaPolicy.Drop -> OutOfQuotaPolicy.DROP_WORK_REQUEST
    }

private fun <B : AndroidWorkRequest.Builder<B, *>> B.applyTags(
    taskId: String,
    periodic: Boolean,
): B {
    addTag(AndroidScheduledTaskMapper.BACKGROUNDER_TAG)
    addTag("${AndroidScheduledTaskMapper.TASK_ID_TAG_PREFIX}$taskId")
    if (periodic) addTag(AndroidScheduledTaskMapper.KIND_PERIODIC_TAG)
    return this
}

private fun Duration.toMillis(): Long = inWholeMilliseconds

private val ANDROID_GUARANTEES =
    SchedulerGuarantees(
        survivesProcessDeath = true,
        survivesReboot = true,
        survivesForceQuit = true,
        honoursWallClock = true, // approximately — Doze / App Standby may delay.
        supportsRetryBackoff = true,
        cancelsInFlight = true,
        minimumPeriodicInterval = WorkRequest.MIN_RECOMMENDED_INTERVAL,
        maxConcurrentTasks = null,
    )
