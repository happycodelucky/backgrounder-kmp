package com.happycodelucky.backgrounder.jvm

import com.happycodelucky.backgrounder.Backgrounder
import com.happycodelucky.backgrounder.SharedBackgrounder
import com.happycodelucky.backgrounder.create
import com.happycodelucky.backgrounder.shared
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** The JVM is a zero-configuration platform: `shared` builds itself on first access. */
class SharedBackgrounderJvmTest {
    @AfterTest
    fun tearDown() {
        SharedBackgrounder.peek()?.shutdown()
        SharedBackgrounder.resetForTests()
    }

    @Test
    fun sharedIsCreatedLazilyAndStable() {
        val first = Backgrounder.shared
        assertSame(first, Backgrounder.shared)
    }

    @Test
    fun explicitCreateBecomesShared() {
        val created = Backgrounder.create()
        assertSame(created, Backgrounder.shared)
    }

    @Test
    fun secondCreateWhileLiveThrows() {
        Backgrounder.create()
        assertFailsWith<IllegalStateException> { Backgrounder.create() }
    }

    @Test
    fun shutdownAllowsAFreshInstance() {
        val first = Backgrounder.shared
        first.shutdown()
        val second = Backgrounder.shared
        assertNotSame(first, second)
    }
}
