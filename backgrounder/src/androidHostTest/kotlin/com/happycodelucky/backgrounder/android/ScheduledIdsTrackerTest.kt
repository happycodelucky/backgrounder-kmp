package com.happycodelucky.backgrounder.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM unit test for [ScheduledIdsTracker]. No Robolectric needed.
 *
 * Reproducer for the cancel-outcome contract violation flagged in review-loop
 * round 1 (H-CONSENSUS-1): the previous `WorkManagerScheduler.cancel()` always
 * returned `Cancelled(1)` regardless of whether the task id was actually
 * registered. This tracker is the seam that makes the correct outcome possible.
 */
class ScheduledIdsTrackerTest {
    private val a = "com.happycodelucky.backgrounder.test.a"
    private val b = "com.happycodelucky.backgrounder.test.b"

    @Test
    fun removeIfPresentReturnsTrueOnlyForTrackedIds() {
        val tracker = ScheduledIdsTracker()
        tracker.add(a)
        assertTrue(tracker.removeIfPresent(a), "first remove should report true")
        assertFalse(tracker.removeIfPresent(a), "second remove should report false")
        assertFalse(tracker.removeIfPresent(b), "remove of unknown id should report false")
    }

    @Test
    fun clearAndCountReturnsTheRightSize() {
        val tracker = ScheduledIdsTracker()
        assertEquals(0, tracker.clearAndCount(), "empty tracker returns zero")
        tracker.add(a)
        tracker.add(b)
        assertEquals(2, tracker.clearAndCount())
        assertEquals(0, tracker.clearAndCount(), "second clear returns zero")
    }

    @Test
    fun addIsIdempotent() {
        val tracker = ScheduledIdsTracker()
        tracker.add(a)
        tracker.add(a)
        assertEquals(1, tracker.snapshot().size, "set semantics — duplicate add does not double-count")
        assertTrue(tracker.removeIfPresent(a))
        assertFalse(tracker.removeIfPresent(a))
    }

    @Test
    fun snapshotIsIndependentOfFutureMutation() {
        val tracker = ScheduledIdsTracker()
        tracker.add(a)
        val snap = tracker.snapshot()
        tracker.add(b)
        assertEquals(setOf(a), snap, "snapshot must not see later additions")
        assertEquals(setOf(a, b), tracker.snapshot())
    }

    @Test
    fun addAndWasPresentReportsPriorTrackingInOneOperation() {
        // Anchor for the ScheduleReplaced race fix: the was-tracked decision
        // and the add must be a single guarded operation, not snapshot-then-add.
        val tracker = ScheduledIdsTracker()
        assertFalse(tracker.addAndWasPresent(a), "first add: not previously tracked")
        assertTrue(tracker.addAndWasPresent(a), "second add: was tracked")
        assertEquals(setOf(a), tracker.snapshot())
        tracker.removeIfPresent(a)
        assertFalse(tracker.addAndWasPresent(a), "re-add after remove: not tracked again")
    }
}
