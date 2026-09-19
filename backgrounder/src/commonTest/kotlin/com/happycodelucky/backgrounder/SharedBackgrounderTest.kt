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
class SharedBackgrounderTest {
    @AfterTest
    fun tearDown() = SharedBackgrounder.resetForTests()

    private fun build(): Backgrounder {
        val ephemeral = EphemeralRegistry(MapSettings())
        return Backgrounder(
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
        SharedBackgrounder.install(a)
        assertSame(a, Backgrounder.shared)
        assertSame(a, Backgrounder.sharedInstance())
    }

    @Test
    fun secondLiveInstanceIsRejected() {
        SharedBackgrounder.install(build())
        assertFailsWith<IllegalStateException> { SharedBackgrounder.install(build()) }
    }

    @Test
    fun shutdownReleasesTheSlot() {
        val a = build()
        SharedBackgrounder.install(a)
        a.shutdown()
        assertNull(SharedBackgrounder.peek())

        val b = build()
        SharedBackgrounder.install(b)
        assertSame(b, Backgrounder.shared)
    }

    @Test
    fun shutdownOfAForeignInstanceLeavesTheSlotAlone() {
        val live = build()
        SharedBackgrounder.install(live)
        build().shutdown()
        assertSame(live, SharedBackgrounder.peek())
    }
}
