package com.happycodelucky.backgrounder

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration.Companion.minutes

/**
 * In-memory [Scheduler] for unit tests. Does not actually run workers; records
 * the requests and lets tests advance through their lifecycle deterministically.
 */
internal class FakeScheduler(
    private val ephemeral: EphemeralRegistry,
) : Scheduler {
    private val lock = SynchronizedObject()
    private val pending: MutableMap<String, WorkRequest> = linkedMapOf()
    private val attempts: MutableMap<String, Int> = mutableMapOf()

    override fun schedule(
        request: WorkRequest,
        policy: ConflictPolicy,
    ): ScheduleOutcome =
        synchronized(lock) {
            if (request is WorkRequest.Periodic && request.interval < 15.minutes) {
                return@synchronized ScheduleOutcome.Rejected(
                    "Periodic interval below 15-minute floor: ${request.interval}",
                )
            }
            val existing = pending[request.taskId]
            if (existing != null && policy == ConflictPolicy.Keep) {
                return@synchronized ScheduleOutcome.Scheduled
            }
            pending[request.taskId] = request
            attempts[request.taskId] = 0
            if (request.ephemeral) ephemeral.add(request.taskId)
            ScheduleOutcome.Scheduled
        }

    override fun cancel(taskId: String): CancelOutcome =
        synchronized(lock) {
            val removed = pending.remove(taskId) != null
            attempts.remove(taskId)
            ephemeral.remove(taskId)
            if (removed) CancelOutcome.Cancelled(pendingCleared = 1) else CancelOutcome.NoSuchTask
        }

    override fun cancelAll(): CancelOutcome =
        synchronized(lock) {
            val cleared = pending.size
            pending.clear()
            attempts.clear()
            ephemeral.clear()
            if (cleared > 0) CancelOutcome.Cancelled(pendingCleared = cleared) else CancelOutcome.NoSuchTask
        }

    override suspend fun scheduled(): List<ScheduledTask> =
        synchronized(lock) {
            pending.values.map { req ->
                ScheduledTask(
                    taskId = req.taskId,
                    kind = if (req is WorkRequest.OneTime) ScheduledTask.Kind.OneTime else ScheduledTask.Kind.Periodic,
                    state = ScheduledTask.State.Pending,
                    nextRunHint = null,
                    attempt = attempts[req.taskId] ?: 0,
                    ephemeral = req.ephemeral,
                )
            }
        }

    override fun guarantees(): SchedulerGuarantees =
        SchedulerGuarantees(
            survivesProcessDeath = true,
            survivesReboot = true,
            survivesForceQuit = true,
            honoursWallClock = true,
            supportsRetryBackoff = true,
            cancelsInFlight = true,
            minimumPeriodicInterval = WorkRequest.MIN_RECOMMENDED_INTERVAL,
            maxConcurrentTasks = null,
        )
}
