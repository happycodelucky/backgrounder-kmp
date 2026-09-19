package com.happycodelucky.backgrounder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class WorkerRegistryTest {
    private val syncId = "com.happycodelucky.backgrounder.test.sync"
    private val uploadId = "com.happycodelucky.backgrounder.test.upload"

    // Object expression (not a SAM lambda) so the runtime hands us a *new*
    // instance per invocation — what the registry contract requires.
    private fun newWorker(): BackgroundWorker =
        object : BackgroundWorker {
            override suspend fun execute(context: WorkerContext): WorkResult = WorkResult.Success
        }

    // A factory that owns [ownedIds] and builds a worker for each. [overrides]
    // lets a test force a specific worker (or null) for an id.
    private class StubFactory(
        override val taskIds: Set<String>,
        private val overrides: Map<String, BackgroundWorker?> = emptyMap(),
        private val build: () -> BackgroundWorker,
    ) : BackgroundWorkerFactory {
        override fun create(taskId: String): BackgroundWorker? =
            when {
                taskId in overrides -> overrides[taskId]
                taskId in taskIds -> build()
                else -> null
            }
    }

    @Test
    fun registerThenCreateReturnsAFreshInstance() {
        val registry = WorkerRegistry()
        registry.register(syncId) { newWorker() }

        val a = registry.create(syncId)
        val b = registry.create(syncId)
        assertNotSame(a, b, "factory should produce a fresh worker per invocation")
    }

    @Test
    fun registeredIdsReflectsRegistrations() {
        val registry = WorkerRegistry()
        registry.register(syncId) { newWorker() }
        registry.register(uploadId) { newWorker() }
        assertEquals(setOf(syncId, uploadId), registry.registeredIds())
    }

    @Test
    fun duplicateRegistrationThrows() {
        val registry = WorkerRegistry()
        registry.register(syncId) { newWorker() }
        assertFailsWith<IllegalArgumentException> {
            registry.register(syncId) { newWorker() }
        }
    }

    @Test
    fun missingFactoryThrows() {
        val registry = WorkerRegistry()
        val missing = "com.happycodelucky.backgrounder.test.never_registered"
        assertFailsWith<WorkerRegistry.NoFactoryRegisteredException> {
            registry.create(missing)
        }
    }

    @Test
    fun sealRejectsFurtherRegistration() {
        val registry = WorkerRegistry()
        registry.register(syncId) { newWorker() }
        registry.seal()
        assertFailsWith<IllegalStateException> {
            registry.register(uploadId) { newWorker() }
        }
        // But existing factories still work post-seal.
        assertEquals(setOf(syncId), registry.registeredIds())
        registry.create(syncId) // does not throw
    }

    @Test
    fun factoryResolvesItsOwnedIds() {
        val registry = WorkerRegistry()
        registry.register(StubFactory(setOf(syncId, uploadId)) { newWorker() })

        val a = registry.create(syncId)
        val b = registry.create(uploadId)
        assertNotSame(a, b)
    }

    @Test
    fun registeredIdsUnionsPerIdAndFactoryIds() {
        val otherId = "com.happycodelucky.backgrounder.test.other"
        val registry = WorkerRegistry()
        registry.register(syncId) { newWorker() }
        registry.register(StubFactory(setOf(uploadId, otherId)) { newWorker() })

        assertEquals(setOf(syncId, uploadId, otherId), registry.registeredIds())
    }

    @Test
    fun perIdRegistrationWinsOverFactory() {
        val registry = WorkerRegistry()
        // Register the per-id worker first; the factory does not declare syncId,
        // so there is no collision — but resolution must still hit the per-id path.
        val perIdWorker = newWorker()
        registry.register(syncId) { perIdWorker }
        registry.register(StubFactory(setOf(uploadId)) { newWorker() })

        assertSame(perIdWorker, registry.create(syncId))
    }

    @Test
    fun factoriesResolveInRegistrationOrder() {
        val registry = WorkerRegistry()
        val first = newWorker()
        val second = newWorker()
        // Two factories, disjoint id sets — each resolves only its own id.
        registry.register(StubFactory(setOf(syncId)) { first })
        registry.register(StubFactory(setOf(uploadId)) { second })

        assertSame(first, registry.create(syncId))
        assertSame(second, registry.create(uploadId))
    }

    @Test
    fun factoryCollidingWithPerIdRegistrationThrows() {
        val registry = WorkerRegistry()
        registry.register(syncId) { newWorker() }
        assertFailsWith<IllegalArgumentException> {
            registry.register(StubFactory(setOf(syncId)) { newWorker() })
        }
    }

    @Test
    fun perIdRegistrationCollidingWithFactoryThrows() {
        val registry = WorkerRegistry()
        registry.register(StubFactory(setOf(syncId)) { newWorker() })
        assertFailsWith<IllegalArgumentException> {
            registry.register(syncId) { newWorker() }
        }
    }

    @Test
    fun overlappingFactoriesThrow() {
        val registry = WorkerRegistry()
        registry.register(StubFactory(setOf(syncId, uploadId)) { newWorker() })
        assertFailsWith<IllegalArgumentException> {
            registry.register(StubFactory(setOf(uploadId)) { newWorker() })
        }
    }

    @Test
    fun emptyFactoryThrows() {
        val registry = WorkerRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.register(StubFactory(emptySet()) { newWorker() })
        }
    }

    @Test
    fun factoryDecliningDeclaredIdThrows() {
        val registry = WorkerRegistry()
        registry.register(
            StubFactory(setOf(syncId), overrides = mapOf(syncId to null)) { newWorker() },
        )
        assertFailsWith<WorkerRegistry.FactoryDeclinedException> {
            registry.create(syncId)
        }
    }

    @Test
    fun sealRejectsFactoryRegistration() {
        val registry = WorkerRegistry()
        registry.seal()
        assertFailsWith<IllegalStateException> {
            registry.register(StubFactory(setOf(syncId)) { newWorker() })
        }
    }

    // Anchor for B-021: create() resolves the factory under the registry lock
    // but invokes it OUTSIDE the lock, so factory code (which is user code) may
    // freely call back into registry read APIs. Guards against a refactor that
    // moves the invocation back inside the critical section.
    @Test
    fun factoryCanCallBackIntoRegistryDuringCreate() {
        val registry = WorkerRegistry()
        var seenDuringPerIdCreate: Set<String>? = null
        registry.register(syncId) {
            seenDuringPerIdCreate = registry.registeredIds()
            newWorker()
        }
        var seenDuringBulkCreate: List<FactoryDescriptor>? = null
        registry.register(
            object : BackgroundWorkerFactory {
                override val taskIds: Set<String> = setOf(uploadId)

                override fun create(taskId: String): BackgroundWorker {
                    seenDuringBulkCreate = registry.factoryDescriptors()
                    return newWorker()
                }
            },
        )

        registry.create(syncId)
        registry.create(uploadId)

        assertEquals(setOf(syncId, uploadId), seenDuringPerIdCreate)
        assertEquals(2, seenDuringBulkCreate?.size)
    }
}
