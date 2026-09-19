package com.happycodelucky.backgrounder

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

/**
 * Task ids are free-form strings; the only rule is non-blank with no
 * surrounding whitespace, enforced at every public entry point.
 */
class TaskIdValidationTest {
    @Test
    fun acceptsAnyNonBlankShape() {
        // Reverse-DNS is the convention, but nothing enforces it.
        listOf("com.example.sync", "sync", "Sync Worker #1", "x.y.z-with_some.123", "a/b:c").forEach { id ->
            requireValidTaskId(id)
            WorkRequest.OneTime(taskId = id)
            WorkRequest.Periodic(taskId = id, interval = 15.minutes)
        }
    }

    @Test
    fun rejectsBlank() {
        assertFailsWith<IllegalArgumentException> { requireValidTaskId("") }
        assertFailsWith<IllegalArgumentException> { requireValidTaskId("   ") }
    }

    @Test
    fun rejectsSurroundingWhitespace() {
        assertFailsWith<IllegalArgumentException> { requireValidTaskId(" com.example.sync") }
        assertFailsWith<IllegalArgumentException> { requireValidTaskId("com.example.sync\n") }
    }

    @Test
    fun rejectsControlCharacters() {
        assertFailsWith<IllegalArgumentException> { requireValidTaskId("com.example\u001Fsync") }
        assertFailsWith<IllegalArgumentException> { requireValidTaskId("com.example\tsync") }
    }

    @Test
    fun workRequestConstructionValidates() {
        assertFailsWith<IllegalArgumentException> { WorkRequest.OneTime(taskId = "") }
        assertFailsWith<IllegalArgumentException> { WorkRequest.Periodic(taskId = " x", interval = 15.minutes) }
    }

    @Test
    fun registryValidates() {
        val registry = WorkerRegistry()
        assertFailsWith<IllegalArgumentException> { registry.register("") { BackgroundWorker { WorkResult.Success } } }
        assertFailsWith<IllegalArgumentException> {
            registry.register(
                object : BackgroundWorkerFactory {
                    override val taskIds: Set<String> = setOf("ok.id", " bad")

                    override fun create(taskId: String): BackgroundWorker? = null
                },
            )
        }
    }
}
