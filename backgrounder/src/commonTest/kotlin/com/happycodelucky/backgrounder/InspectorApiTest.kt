package com.happycodelucky.backgrounder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for the wave-3 inspector additions that live entirely in
 * commonMain: [WorkerRegistry.factoryDescriptors], the default
 * [BackgroundWorkerFactory.factoryId] (= null), and the
 * [FactoryDescriptor] shape. Per-platform diagnostics + pendingPredicates
 * surfaces are exercised by their own platform test suites.
 */
class InspectorApiTest {
    private val taskA = "com.example.a"
    private val taskB = "com.example.b"
    private val taskC = "com.example.c"

    private val noopWorker =
        object : BackgroundWorker {
            override suspend fun execute(context: WorkerContext): WorkResult = WorkResult.Success
        }

    @Test
    fun perId_registrations_produce_PerId_descriptors_sorted() {
        val registry = WorkerRegistry()
        registry.register(taskB) { noopWorker }
        registry.register(taskA) { noopWorker }
        val descriptors = registry.factoryDescriptors()
        assertEquals(2, descriptors.size)
        assertEquals(
            listOf(taskA, taskB),
            descriptors.filterIsInstance<FactoryDescriptor.PerId>().map { it.taskId },
            "PerId descriptors should be sorted ascending by String for stable inspector output",
        )
        descriptors.forEach { d ->
            assertEquals(null, d.factoryId, "PerId closures have no factoryId")
        }
    }

    @Test
    fun bulk_factory_with_factoryId_produces_Bulk_descriptor() {
        val factory =
            object : BackgroundWorkerFactory {
                override val factoryId: String = "auth-module"
                override val taskIds: Set<String> = setOf(taskA, taskB)

                override fun create(taskId: String): BackgroundWorker = noopWorker
            }
        val registry = WorkerRegistry()
        registry.register(factory)
        val descriptors = registry.factoryDescriptors()
        assertEquals(1, descriptors.size)
        val bulk = descriptors.single() as FactoryDescriptor.Bulk
        assertEquals("auth-module", bulk.factoryId)
        assertEquals(setOf(taskA, taskB), bulk.taskIds)
    }

    @Test
    fun bulk_factory_without_factoryId_falls_back_to_null() {
        val factory =
            object : BackgroundWorkerFactory {
                override val taskIds: Set<String> = setOf(taskC)

                override fun create(taskId: String): BackgroundWorker = noopWorker
            }
        val registry = WorkerRegistry()
        registry.register(factory)
        assertEquals(null, (registry.factoryDescriptors().single() as FactoryDescriptor.Bulk).factoryId)
    }

    @Test
    fun mixed_registrations_descriptor_order_is_perid_then_bulk() {
        val factory =
            object : BackgroundWorkerFactory {
                override val factoryId: String = "module"
                override val taskIds: Set<String> = setOf(taskC)

                override fun create(taskId: String): BackgroundWorker = noopWorker
            }
        val registry = WorkerRegistry()
        registry.register(factory)
        registry.register(taskA) { noopWorker }
        val descriptors = registry.factoryDescriptors()
        assertTrue(descriptors[0] is FactoryDescriptor.PerId, "PerId must come first")
        assertTrue(descriptors[1] is FactoryDescriptor.Bulk, "Bulk after")
    }

    @Test
    fun scheduledTaskDefaultsPendingPredicatesToEmpty() {
        val task =
            ScheduledTask(
                taskId = taskA,
                kind = ScheduledTask.Kind.OneTime,
                state = ScheduledTask.State.Pending,
                nextRunHint = null,
                attempt = 0,
                ephemeral = false,
            )
        assertTrue(task.pendingPredicates.isEmpty())
    }

    @Test
    fun platformDiagnosticsIsHealthyReflectsEmptiness() {
        assertTrue(PlatformDiagnostics.Healthy.isHealthy)
        assertTrue(PlatformDiagnostics(emptyList()).isHealthy)
        assertTrue(!PlatformDiagnostics(listOf(PlatformDiagnostic.RegistryNotSealed)).isHealthy)
    }
}
