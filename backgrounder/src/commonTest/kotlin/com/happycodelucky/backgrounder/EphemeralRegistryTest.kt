package com.happycodelucky.backgrounder

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EphemeralRegistryTest {
    private val a = "com.happycodelucky.backgrounder.test.a"
    private val b = "com.happycodelucky.backgrounder.test.b"
    private val c = "com.happycodelucky.backgrounder.test.c"

    @Test
    fun emptyByDefault() {
        val registry = EphemeralRegistry(MapSettings())
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun addAndSnapshotRoundTrip() {
        val registry = EphemeralRegistry(MapSettings())
        registry.add(a)
        registry.add(b)
        assertEquals(setOf(a, b), registry.snapshot())
    }

    @Test
    fun addingDuplicatesIsIdempotent() {
        val registry = EphemeralRegistry(MapSettings())
        registry.add(a)
        registry.add(a)
        registry.add(a)
        assertEquals(setOf(a), registry.snapshot())
    }

    @Test
    fun removeShrinks() {
        val registry = EphemeralRegistry(MapSettings())
        registry.add(a)
        registry.add(b)
        registry.add(c)
        registry.remove(b)
        assertEquals(setOf(a, c), registry.snapshot())
    }

    @Test
    fun clearWipes() {
        val registry = EphemeralRegistry(MapSettings())
        registry.add(a)
        registry.add(b)
        registry.clear()
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun concurrentAddsDoNotLoseEntries() =
        runTest {
            // Without the lock, two concurrent add() calls can both read the
            // pre-add snapshot and one wins, dropping the other. Run a
            // moderately wide fan-out on a real dispatcher so the worker threads
            // actually contend.
            val registry = EphemeralRegistry(MapSettings())
            val ids = (0 until 64).map { "com.happycodelucky.backgrounder.test.t$it" }
            withContext(Dispatchers.Default) {
                ids
                    .map { id -> async { registry.add(id) } }
                    .awaitAll()
            }
            assertEquals(ids.toSet(), registry.snapshot())
        }

    @Test
    fun persistsAcrossInstances() {
        val backing = MapSettings()
        EphemeralRegistry(backing).apply {
            add(a)
            add(b)
        }
        // A fresh registry over the same backing storage sees the writes.
        assertEquals(setOf(a, b), EphemeralRegistry(backing).snapshot())
    }
}
