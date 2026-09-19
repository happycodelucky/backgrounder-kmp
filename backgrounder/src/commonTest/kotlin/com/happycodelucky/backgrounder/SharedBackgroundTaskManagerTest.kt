package com.happycodelucky.backgrounder

import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The process-wide slot: one live instance, claimed by the platform builders,
 * released by `shutdown()`. Platform-specific population (lazy creation,
 * Android's "not configured" error) is covered in the platform test sets.
 */
class SharedBackgroundTaskManagerTest {
    @AfterTest
    fun tearDown() = SharedBackgroundTaskManager.resetForTests()

    private fun build(): BackgroundTaskManager {
        val ephemeral = EphemeralRegistry(MapSettings())
        return BackgroundTaskManager(
            BackgrounderEngine(
                registry = WorkerRegistry(),
                scheduler = FakeScheduler(ephemeral),
                instantRunner = FakeInstantRunner(),
                emitter = MonitorEventEmitter(BackgrounderEventListener.Noop),
                onStart = {},
                onShutdown = {},
            ),
        )
    }

    @Test
    fun installedInstanceIsShared() {
        val a = build()
        SharedBackgroundTaskManager.install(a)
        assertSame(a, BackgroundTaskManager.shared)
        assertSame(a, BackgroundTaskManager.sharedInstance())
    }

    @Test
    fun secondLiveInstanceIsRejected() {
        SharedBackgroundTaskManager.install(build())
        assertFailsWith<IllegalStateException> { SharedBackgroundTaskManager.install(build()) }
    }

    @Test
    fun shutdownReleasesTheSlot() {
        val a = build()
        SharedBackgroundTaskManager.install(a)
        a.shutdown()
        assertNull(SharedBackgroundTaskManager.peek())

        val b = build()
        SharedBackgroundTaskManager.install(b)
        assertSame(b, BackgroundTaskManager.shared)
    }

    @Test
    fun shutdownOfAForeignInstanceLeavesTheSlotAlone() {
        val live = build()
        SharedBackgroundTaskManager.install(live)
        build().shutdown()
        assertSame(live, SharedBackgroundTaskManager.peek())
    }
}
