package com.happycodelucky.backgrounder.ios

import com.happycodelucky.backgrounder.NetworkRequirement
import com.happycodelucky.backgrounder.WorkInput
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Anchor for the process-death contract (D-020/D-022) on iOS: a one-shot whose
 * run died with a previous process (active in the store, no pending OS
 * request) is cleared at launch; pending-but-unfired one-shots and periodics
 * are untouched. Drives [IOSOneShotReconciliation.apply] directly with a fake
 * pending set — the real `BGTaskScheduler` query needs a live app host.
 */
class IOSOneShotReconciliationTest {
    private val deadId = "com.happycodelucky.backgrounder.test.dead"
    private val pendingId = "com.happycodelucky.backgrounder.test.pending"
    private val periodicId = "com.happycodelucky.backgrounder.test.periodic"

    private fun IOSStateStore.writeOneShot(taskId: String) {
        writeOnSchedule(
            taskId = taskId,
            kind = IOSStateStore.Kind.OneShot,
            input = WorkInput.empty(),
            ephemeral = false,
            intervalMs = null,
            nextRunEpochMs = 1_767_225_600_000L,
            networkRequired = NetworkRequirement.None,
        )
    }

    @Test
    fun deadOneShotIsClearedAndPendingOneShotKept() {
        val state = IOSStateStore(MapSettings())
        val mutexes = IOSTaskMutexes()
        state.writeOneShot(deadId)
        state.writeOneShot(pendingId)
        val reconciliation = IOSOneShotReconciliation(state, mutexes)

        val candidates = reconciliation.snapshotCandidates()
        assertEquals(setOf(deadId, pendingId), candidates.toSet())

        // Only `pendingId` has a pending OS request — `deadId`'s was consumed
        // by the BGTask launch that then died with the process.
        reconciliation.apply(candidates, pendingIdentifiers = setOf(pendingId))

        assertFalse(state.readActive(deadId), "dead one-shot must be cleared")
        assertEquals(null, state.readKind(deadId), "dead one-shot state fully wiped")
        assertTrue(state.readActive(pendingId), "pending one-shot is not interrupted; kept")
    }

    @Test
    fun periodicsAreNeverCandidates() {
        val state = IOSStateStore(MapSettings())
        state.writeOnSchedule(
            taskId = periodicId,
            kind = IOSStateStore.Kind.Periodic,
            input = WorkInput.empty(),
            ephemeral = false,
            intervalMs = 900_000L,
            nextRunEpochMs = 1_767_225_600_000L,
            networkRequired = NetworkRequirement.None,
        )
        val reconciliation = IOSOneShotReconciliation(state, IOSTaskMutexes())

        assertTrue(
            reconciliation.snapshotCandidates().isEmpty(),
            "periodics have no per-id OS request to reconcile against; resurrection owns them",
        )
    }

    @Test
    fun tasksScheduledAfterTheSnapshotAreNeverSwept() {
        val state = IOSStateStore(MapSettings())
        val reconciliation = IOSOneShotReconciliation(state, IOSTaskMutexes())

        val candidates = reconciliation.snapshotCandidates() // empty snapshot
        state.writeOneShot(pendingId) // scheduled after the snapshot
        reconciliation.apply(candidates, pendingIdentifiers = emptySet())

        assertTrue(state.readActive(pendingId), "post-snapshot schedule must survive a late callback")
    }
}
