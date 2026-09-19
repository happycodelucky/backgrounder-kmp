package com.happycodelucky.backgrounder.android

import androidx.work.WorkInfo
import com.happycodelucky.backgrounder.ScheduledTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [AndroidScheduledTaskMapper] — no Robolectric.
 *
 * Exercises the WorkInfo → ScheduledTask transform via the test-friendly
 * [AndroidScheduledTaskMapper.WorkInfoView] entry point so we don't have to
 * construct a real `WorkInfo` (which has 13+ required positional parameters
 * and a constructor shape that has shifted across androidx.work versions).
 *
 * Reproducer test for review-loop round 1, finding H-CONSENSUS-2: the
 * previous test referenced an `AndroidScheduledTaskMapper` object that
 * didn't exist. This is the rebuild against the now-extracted mapper.
 */
class AndroidScheduledTaskMapperTest {
    private val taskId = "com.happycodelucky.backgrounder.test.sync"
    private val taskIdTag = "${AndroidScheduledTaskMapper.TASK_ID_TAG_PREFIX}$taskId"

    private fun view(
        tags: Set<String>,
        state: WorkInfo.State = WorkInfo.State.ENQUEUED,
        runAttemptCount: Int = 0,
        nextScheduleTimeMillis: Long = 0L,
    ) = AndroidScheduledTaskMapper.WorkInfoView(
        tags = tags,
        state = state,
        runAttemptCount = runAttemptCount,
        nextScheduleTimeMillis = nextScheduleTimeMillis,
    )

    @Test
    fun returnsNullWhenNoTaskIdTagPresent() {
        // Unrelated work the user might have scheduled with the same WorkManager.
        val v = view(tags = setOf("user.app.feature.flag"))
        assertNull(AndroidScheduledTaskMapper.fromView(v, ephemeralIds = emptySet()))
    }

    @Test
    fun mapsOneTimeEnqueuedAsPending() {
        val v = view(tags = setOf(AndroidScheduledTaskMapper.BACKGROUNDER_TAG, taskIdTag))
        val task = AndroidScheduledTaskMapper.fromView(v, ephemeralIds = emptySet())!!
        assertEquals(taskId, task.taskId)
        assertEquals(ScheduledTask.Kind.OneTime, task.kind)
        assertEquals(ScheduledTask.State.Pending, task.state)
        assertEquals(0, task.attempt)
        assertNull(task.nextRunHint)
        assertEquals(false, task.ephemeral)
    }

    @Test
    fun mapsPeriodicTagAsPeriodic() {
        val v =
            view(
                tags =
                    setOf(
                        AndroidScheduledTaskMapper.BACKGROUNDER_TAG,
                        taskIdTag,
                        AndroidScheduledTaskMapper.KIND_PERIODIC_TAG,
                    ),
            )
        val task = AndroidScheduledTaskMapper.fromView(v, ephemeralIds = emptySet())!!
        assertEquals(ScheduledTask.Kind.Periodic, task.kind)
    }

    @Test
    fun enqueuedWithPriorAttemptsMapsToBackoff() {
        val v =
            view(
                tags = setOf(AndroidScheduledTaskMapper.BACKGROUNDER_TAG, taskIdTag),
                state = WorkInfo.State.ENQUEUED,
                runAttemptCount = 2,
            )
        val task = AndroidScheduledTaskMapper.fromView(v, ephemeralIds = emptySet())!!
        assertEquals(ScheduledTask.State.Backoff, task.state)
        assertEquals(2, task.attempt)
    }

    @Test
    fun runningStateMapsToRunning() {
        val v =
            view(
                tags = setOf(AndroidScheduledTaskMapper.BACKGROUNDER_TAG, taskIdTag),
                state = WorkInfo.State.RUNNING,
            )
        val task = AndroidScheduledTaskMapper.fromView(v, ephemeralIds = emptySet())!!
        assertEquals(ScheduledTask.State.Running, task.state)
    }

    @Test
    fun blockedStateMapsToBlocked() {
        val v =
            view(
                tags = setOf(AndroidScheduledTaskMapper.BACKGROUNDER_TAG, taskIdTag),
                state = WorkInfo.State.BLOCKED,
            )
        val task = AndroidScheduledTaskMapper.fromView(v, ephemeralIds = emptySet())!!
        assertEquals(ScheduledTask.State.Blocked, task.state)
    }

    @Test
    fun terminalStatesDefendDefensivelyAsPending() {
        // The query filters these out via WorkQuery — but if WorkManager returns
        // them anyway (race during the snapshot window), we map defensively.
        listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED).forEach { terminal ->
            val v =
                view(
                    tags = setOf(AndroidScheduledTaskMapper.BACKGROUNDER_TAG, taskIdTag),
                    state = terminal,
                )
            val task = AndroidScheduledTaskMapper.fromView(v, ephemeralIds = emptySet())!!
            assertEquals(ScheduledTask.State.Pending, task.state, "$terminal should map to Pending")
        }
    }

    @Test
    fun nextScheduleTimeMillisMapsToInstant() {
        val v =
            view(
                tags = setOf(AndroidScheduledTaskMapper.BACKGROUNDER_TAG, taskIdTag),
                nextScheduleTimeMillis = 1_700_000_000_000L,
            )
        val task = AndroidScheduledTaskMapper.fromView(v, ephemeralIds = emptySet())!!
        assertEquals(1_700_000_000_000L, task.nextRunHint?.toEpochMilliseconds())
    }

    @Test
    fun zeroOrNegativeNextScheduleTimeMillisYieldsNullHint() {
        val v =
            view(
                tags = setOf(AndroidScheduledTaskMapper.BACKGROUNDER_TAG, taskIdTag),
                nextScheduleTimeMillis = 0L,
            )
        val task = AndroidScheduledTaskMapper.fromView(v, ephemeralIds = emptySet())!!
        assertNull(task.nextRunHint)
    }

    @Test
    fun ephemeralFlagSetWhenIdInSnapshot() {
        val v = view(tags = setOf(AndroidScheduledTaskMapper.BACKGROUNDER_TAG, taskIdTag))
        val task = AndroidScheduledTaskMapper.fromView(v, ephemeralIds = setOf(taskId))!!
        assertTrue(task.ephemeral)
    }
}
