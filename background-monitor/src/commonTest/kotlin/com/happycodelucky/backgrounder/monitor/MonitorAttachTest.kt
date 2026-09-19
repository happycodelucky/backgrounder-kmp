package com.happycodelucky.backgrounder.monitor

import com.happycodelucky.backgrounder.MonitorEvent
import com.happycodelucky.backgrounder.WorkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Unit coverage for [AttachedMonitor] (and the collector shape
 * `attachMonitor` uses). The real `attachMonitor(...)` operates on a
 * [com.happycodelucky.backgrounder.BackgroundTaskManager] instance, which we can't
 * cheaply construct without a real platform engine — but its body is
 * trivial: `scope.launch { events().collect { monitor.onEvent(it) } }`,
 * wrapped in `AttachedMonitor(job)`. We exercise the same shape here
 * against a directly-driven `MutableSharedFlow`. That covers:
 *
 *  - delivery of every emitted event;
 *  - `detach()` stopping further delivery;
 *  - `detach()` idempotency;
 *  - multiple subscribers running independently;
 *  - `isActive` mirroring the underlying job.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorAttachTest {
    private val taskId = "com.example.task"

    private fun event(now: Instant = Instant.fromEpochMilliseconds(0)): MonitorEvent =
        MonitorEvent.Scheduled(
            taskId = taskId,
            at = now,
            request = WorkRequest.OneTime(taskId = taskId, initialDelay = 0.seconds),
        )

    @Test
    fun attached_monitor_receives_events() =
        runTest {
            val source = newSource()
            val monitor = RecordingMonitor()
            val attached = subscribe(this, source, monitor)

            source.tryEmit(event())
            source.tryEmit(event())
            testScheduler.advanceUntilIdle()

            assertEquals(2, monitor.received.size)
            assertTrue(attached.isActive)
            attached.detach()
        }

    @Test
    fun detach_stops_further_delivery() =
        runTest {
            val source = newSource()
            val monitor = RecordingMonitor()
            val attached = subscribe(this, source, monitor)

            source.tryEmit(event())
            testScheduler.advanceUntilIdle()
            attached.detach()
            testScheduler.advanceUntilIdle()
            source.tryEmit(event())
            testScheduler.advanceUntilIdle()

            assertEquals(1, monitor.received.size)
            assertTrue(!attached.isActive)
        }

    @Test
    fun multiple_monitors_each_see_every_event() =
        runTest {
            val source = newSource()
            val m1 = RecordingMonitor()
            val m2 = RecordingMonitor()
            val a1 = subscribe(this, source, m1)
            val a2 = subscribe(this, source, m2)

            source.tryEmit(event())
            testScheduler.advanceUntilIdle()
            source.tryEmit(event())
            testScheduler.advanceUntilIdle()

            assertEquals(2, m1.received.size)
            assertEquals(2, m2.received.size)
            a1.detach()
            a2.detach()
        }

    @Test
    fun detach_is_idempotent() =
        runTest {
            val source = newSource()
            val attached = subscribe(this, source, RecordingMonitor())
            attached.detach()
            attached.detach() // must not throw
            assertTrue(!attached.isActive)
        }

    @Test
    fun isActive_flips_false_after_detach() =
        runTest {
            val source = newSource()
            val attached = subscribe(this, source, RecordingMonitor())
            assertTrue(attached.isActive)
            attached.detach()
            testScheduler.advanceUntilIdle()
            assertTrue(!attached.isActive)
        }

    /**
     * Mirrors the shape of the real `attachMonitor` extension's body, *and*
     * waits for the collector to register as a subscriber before returning.
     * Without the await, `tryEmit` on a `replay=0` flow would silently drop
     * events emitted before the launched collect coroutine actually
     * subscribes.
     */
    private suspend fun subscribe(
        scope: CoroutineScope,
        source: MutableSharedFlow<MonitorEvent>,
        monitor: Monitor,
    ): AttachedMonitor {
        val priorCount = source.subscriptionCount.value
        val job: Job =
            scope.launch {
                source.collect { event -> monitor.onEvent(event) }
            }
        // Wait until the new collector has registered itself.
        source.subscriptionCount.first { it > priorCount }
        return AttachedMonitor(job)
    }

    private fun newSource(): MutableSharedFlow<MonitorEvent> =
        MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private class RecordingMonitor : Monitor {
        val received: MutableList<MonitorEvent> = mutableListOf()

        override suspend fun onEvent(event: MonitorEvent) {
            received.add(event)
        }
    }
}
