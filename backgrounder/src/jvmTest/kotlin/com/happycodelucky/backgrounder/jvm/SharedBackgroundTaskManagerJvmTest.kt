package com.happycodelucky.backgrounder.jvm

import com.happycodelucky.backgrounder.BackgroundTaskManager
import com.happycodelucky.backgrounder.SharedBackgroundTaskManager
import com.happycodelucky.backgrounder.create
import com.happycodelucky.backgrounder.shared
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** The JVM is a zero-configuration platform: `shared` builds itself on first access. */
class SharedBackgroundTaskManagerJvmTest {
    @AfterTest
    fun tearDown() {
        SharedBackgroundTaskManager.peek()?.shutdown()
        SharedBackgroundTaskManager.resetForTests()
    }

    @Test
    fun sharedIsCreatedLazilyAndStable() {
        val first = BackgroundTaskManager.shared
        assertSame(first, BackgroundTaskManager.shared)
    }

    @Test
    fun explicitCreateBecomesShared() {
        val created = BackgroundTaskManager.create()
        assertSame(created, BackgroundTaskManager.shared)
    }

    @Test
    fun secondCreateWhileLiveThrows() {
        BackgroundTaskManager.create()
        assertFailsWith<IllegalStateException> { BackgroundTaskManager.create() }
    }

    @Test
    fun shutdownAllowsAFreshInstance() {
        val first = BackgroundTaskManager.shared
        first.shutdown()
        val second = BackgroundTaskManager.shared
        assertNotSame(first, second)
    }
}
