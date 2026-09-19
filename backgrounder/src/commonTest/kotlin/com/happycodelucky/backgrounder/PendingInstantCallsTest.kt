package com.happycodelucky.backgrounder

import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [PendingInstantCalls] — the single-slot, per-task id
 * registry the platform instant runners share.
 */
class PendingInstantCallsTest {
    private val taskId = "com.happycodelucky.backgrounder.test.runNow"
    private val otherId = "com.happycodelucky.backgrounder.test.other"

    private fun newEntry(id: String = taskId): PendingInstantCalls.Entry =
        PendingInstantCalls.Entry(
            taskId = id,
            task = { "ok" },
            deferred = CompletableDeferred<Any?>(),
        )

    @Test
    fun putReturnsNullWhenSlotIsEmpty() {
        val pending = PendingInstantCalls()
        assertNull(pending.put(newEntry()))
    }

    @Test
    fun putReturnsPriorEntryWhenSlotIsOccupied() {
        val pending = PendingInstantCalls()
        val first = newEntry()
        val second = newEntry()
        assertNull(pending.put(first))
        val displaced = pending.put(second)
        assertSame(first, displaced, "put must return the displaced entry, not the new one")
    }

    @Test
    fun takeRemovesAndReturns() {
        val pending = PendingInstantCalls()
        val entry = newEntry()
        pending.put(entry)
        assertSame(entry, pending.take(taskId))
        assertNull(pending.take(taskId), "second take must return null — slot is empty")
    }

    @Test
    fun peekReadsWithoutRemoving() {
        val pending = PendingInstantCalls()
        val entry = newEntry()
        pending.put(entry)
        assertSame(entry, pending.peek(taskId))
        assertSame(entry, pending.peek(taskId), "peek must not consume the slot")
        assertSame(entry, pending.take(taskId), "slot still occupied after multiple peeks")
    }

    @Test
    fun differentTaskIdsHaveIndependentSlots() {
        val pending = PendingInstantCalls()
        val a = newEntry(taskId)
        val b = newEntry(otherId)
        pending.put(a)
        pending.put(b)
        assertSame(a, pending.take(taskId))
        assertSame(b, pending.take(otherId))
    }

    @Test
    fun removeIfSameOnlyClearsWhenIdentityMatches() {
        val pending = PendingInstantCalls()
        val first = newEntry()
        pending.put(first)
        val second = newEntry()
        pending.put(second) // displaces first

        // first is no longer in the slot — removeIfSame(first) must be a no-op.
        assertFalse(pending.removeIfSame(taskId, first))
        assertNotNull(pending.peek(taskId), "second must still be in the slot")

        // removeIfSame(second) clears.
        assertTrue(pending.removeIfSame(taskId, second))
        assertNull(pending.peek(taskId))
    }

    @Test
    fun entryJobAndPlatformHandleAreSettable() {
        val entry = newEntry()
        assertNull(entry.job)
        assertNull(entry.platformHandle)
        // The atomicfu-backed properties must accept set after construction —
        // platform runners write these between submission and dispatch.
        entry.platformHandle = "some-handle"
        assertEquals("some-handle", entry.platformHandle)
    }
}
