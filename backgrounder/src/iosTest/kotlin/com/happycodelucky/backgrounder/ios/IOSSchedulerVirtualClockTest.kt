// ExperimentalForeignApi: the injected submit seam's signature references the
// cinterop type `BGTaskRequest`. Stable in practice (see BGSubmitResult.kt).
@file:OptIn(ExperimentalForeignApi::class)

package com.happycodelucky.backgrounder.ios

import com.happycodelucky.backgrounder.BackgrounderEventListener
import com.happycodelucky.backgrounder.BackoffPolicy
import com.happycodelucky.backgrounder.ConflictPolicy
import com.happycodelucky.backgrounder.EphemeralRegistry
import com.happycodelucky.backgrounder.MonitorEventEmitter
import com.happycodelucky.backgrounder.ReachabilityGate
import com.happycodelucky.backgrounder.WorkRequest
import com.happycodelucky.backgrounder.WorkResult
import com.happycodelucky.backgrounder.WorkerRegistry
import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.Transport
import com.happycodelucky.reachable.testing.FakeReachability
import com.russhwolf.settings.MapSettings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Anchor for the N-011 fix: every schedule/retry timestamp the iOS scheduler
 * persists is computed from its *injected* clock, never from a direct
 * wall-clock read — so virtual-time tests can assert exact values. Before the
 * fix, `IOSBackoffEmulation` and `schedulePeriodic` read `Clock.System.now()`
 * directly and these assertions were impossible (see LESSONS.md B-020/N-011).
 */
class IOSSchedulerVirtualClockTest {
    private val taskId = "com.happycodelucky.backgrounder.test.virtualclock"

    // Same recognisable base as IOSPeriodicDispatcherTest (2026-01-01).
    private val epochBase: Long = 1_767_225_600_000L

    private class NoopListener : BackgrounderEventListener {
        override fun onScheduled(
            taskId: String,
            request: WorkRequest,
        ) = Unit

        override fun onStarted(
            taskId: String,
            attempt: Int,
        ) = Unit

        override fun onCompleted(
            taskId: String,
            attempt: Int,
            result: WorkResult,
        ) = Unit

        override fun onCancelled(taskId: String) = Unit
    }

    private class Rig(
        val scheduler: BGTaskBackedScheduler,
        val store: IOSStateStore,
    )

    private fun newRig(clock: () -> Long): Rig {
        val store = IOSStateStore(MapSettings())
        val mutexes = IOSTaskMutexes()
        val ephemeral = EphemeralRegistry(MapSettings())
        val registry = WorkerRegistry()
        val emitter = MonitorEventEmitter(NoopListener())
        val gate =
            ReachabilityGate(
                FakeReachability(
                    ReachabilityStatus(isReachable = true, transport = Transport.Wifi, isDataMetered = false),
                ),
            )
        val dispatcher =
            IOSPeriodicDispatcher(
                state = store,
                mutexes = mutexes,
                registry = registry,
                ephemeral = ephemeral,
                emitter = emitter,
                gate = gate,
                clock = clock,
            )
        val scheduler =
            BGTaskBackedScheduler(
                state = store,
                mutexes = mutexes,
                ephemeral = ephemeral,
                emitter = emitter,
                backgroundFeed = IOSBackgroundFeed("com.happycodelucky.backgrounder.test.tick", dispatcher),
                foregroundFeed = IOSForegroundFeed(dispatcher),
                submitRequest = { BGSubmitResult.Success },
                clock = clock,
            )
        return Rig(scheduler, store)
    }

    @Test
    fun oneTimeInitialDelayComputedFromInjectedClock() {
        val rig = newRig(clock = { epochBase })

        rig.scheduler.schedule(
            WorkRequest.OneTime(taskId = taskId, initialDelay = 5.minutes),
            ConflictPolicy.Replace,
        )

        assertEquals(epochBase + 5.minutes.inWholeMilliseconds, rig.store.readNextRunEpochMs(taskId))
    }

    @Test
    fun periodicNextRunComputedFromInjectedClock() {
        val rig = newRig(clock = { epochBase })

        rig.scheduler.schedule(
            WorkRequest.Periodic(taskId = taskId, interval = 15.minutes),
            ConflictPolicy.Replace,
        )

        assertEquals(epochBase + 15.minutes.inWholeMilliseconds, rig.store.readNextRunEpochMs(taskId))
    }

    @Test
    fun backoffEmulationIsPureMathOverInjectedNow() {
        val policy = BackoffPolicy.exponential(30.seconds)

        // delayFor(attempt) is 0-indexed: 30s, 60s, 120s, …
        assertEquals(epochBase + 30_000, IOSBackoffEmulation.nextRunEpochMs(policy, 0, nowMs = epochBase))
        assertEquals(epochBase + 60_000, IOSBackoffEmulation.nextRunEpochMs(policy, 1, nowMs = epochBase))
        assertEquals(epochBase + 120_000, IOSBackoffEmulation.nextRunEpochMs(policy, 2, nowMs = epochBase))
        assertEquals(epochBase + 300_000, IOSBackoffEmulation.epochMillisAt(5.minutes, nowMs = epochBase))
    }
}
