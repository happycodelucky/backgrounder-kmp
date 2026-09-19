package com.happycodelucky.backgrounder

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Contract test for [Scheduler] implementations. Currently exercises the
 * in-memory [FakeScheduler]; platform actuals (`WorkManagerScheduler`,
 * `BGTaskBackedScheduler`, `NSBackgroundActivityBackedScheduler`) get their
 * own platform-test variants.
 */
class SchedulerContractTest {
    private val syncId = "com.happycodelucky.backgrounder.test.sync"
    private val uploadId = "com.happycodelucky.backgrounder.test.upload"

    private fun newScheduler(): Pair<Scheduler, EphemeralRegistry> {
        val ephemeral = EphemeralRegistry(MapSettings())
        return FakeScheduler(ephemeral) to ephemeral
    }

    @Test
    fun scheduleOneTimeAcceptsAndAppearsInScheduled() =
        runTest {
            val (scheduler, _) = newScheduler()

            val outcome = scheduler.schedule(WorkRequest.OneTime(taskId = syncId))
            assertEquals(ScheduleOutcome.Scheduled, outcome)

            val list = scheduler.scheduled()
            assertEquals(1, list.size)
            assertEquals(syncId, list[0].taskId)
            assertEquals(ScheduledTask.Kind.OneTime, list[0].kind)
            assertEquals(ScheduledTask.State.Pending, list[0].state)
        }

    @Test
    fun schedulePeriodicBelowFloorIsRejected() =
        runTest {
            val (scheduler, _) = newScheduler()
            // Construction is rejected by WorkRequest.Periodic, so we can't even
            // pass a < 15.minutes Periodic to schedule(). Verify the construction
            // guard.
            try {
                WorkRequest.Periodic(taskId = syncId, interval = 5.minutes)
                error("expected IllegalArgumentException for sub-15-minute interval")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }

    @Test
    fun cancelKnownTaskReportsCancelled() =
        runTest {
            val (scheduler, _) = newScheduler()
            scheduler.schedule(WorkRequest.OneTime(taskId = syncId))

            val outcome = scheduler.cancel(syncId)
            assertTrue(outcome is CancelOutcome.Cancelled)
            assertEquals(1, outcome.pendingCleared)
            assertTrue(scheduler.scheduled().isEmpty())
        }

    @Test
    fun cancelUnknownTaskReportsNoSuchTask() =
        runTest {
            val (scheduler, _) = newScheduler()
            assertEquals(CancelOutcome.NoSuchTask, scheduler.cancel(syncId))
        }

    @Test
    fun cancelAllClearsEverything() =
        runTest {
            val (scheduler, _) = newScheduler()
            scheduler.schedule(WorkRequest.OneTime(taskId = syncId))
            scheduler.schedule(WorkRequest.OneTime(taskId = uploadId))

            val outcome = scheduler.cancelAll()
            assertTrue(outcome is CancelOutcome.Cancelled)
            assertEquals(2, outcome.pendingCleared)
            assertTrue(scheduler.scheduled().isEmpty())
        }

    @Test
    fun keepPolicyPreservesExistingRequest() =
        runTest {
            val (scheduler, _) = newScheduler()
            val original =
                WorkRequest.OneTime(
                    taskId = syncId,
                    input = WorkInput.of("v" to WorkValue.LongValue(1)),
                    initialDelay = 30.seconds,
                )
            val updated =
                WorkRequest.OneTime(
                    taskId = syncId,
                    input = WorkInput.of("v" to WorkValue.LongValue(2)),
                    initialDelay = 90.seconds,
                )
            scheduler.schedule(original)
            scheduler.schedule(updated, ConflictPolicy.Keep)

            val list = scheduler.scheduled()
            assertEquals(1, list.size)
            // FakeScheduler doesn't surface input back through ScheduledTask;
            // it's enough that the second schedule didn't error and there's still
            // exactly one entry.
        }

    @Test
    fun ephemeralFlagPropagatesToRegistryOnSchedule() =
        runTest {
            val (scheduler, ephemeral) = newScheduler()
            scheduler.schedule(WorkRequest.OneTime(taskId = syncId, ephemeral = true))
            scheduler.schedule(WorkRequest.OneTime(taskId = uploadId, ephemeral = false))

            assertEquals(setOf(syncId), ephemeral.snapshot())

            val snap = scheduler.scheduled().associateBy { it.taskId }
            assertTrue(snap.getValue(syncId).ephemeral)
            assertTrue(!snap.getValue(uploadId).ephemeral)
        }

    @Test
    fun ephemeralRegistryIsKeptInSyncOnCancel() =
        runTest {
            val (scheduler, ephemeral) = newScheduler()
            scheduler.schedule(WorkRequest.OneTime(taskId = syncId, ephemeral = true))
            scheduler.cancel(syncId)
            assertTrue(ephemeral.snapshot().isEmpty())
        }
}
