package com.happycodelucky.backgrounder

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * The DI seam: resolves a stable task id to a fresh [BackgroundWorker] per
 * invocation. Two registration shapes feed it:
 *
 *  - **Per-id**: [register] taking a task id + closure. One id, one factory
 *    lambda. The closure closes over the user's DI graph.
 *  - **Bulk**: [register] taking a [BackgroundWorkerFactory]. One factory
 *    object owns many ids and resolves the concrete worker lazily.
 *
 * The library calls the resolved factory each time the platform fires a
 * worker — never caches the worker instance itself.
 *
 * **Resolution order** in [create]: per-id registrations first, then
 * factories in registration order, first non-null wins. Declared id sets
 * must not overlap (factory-vs-factory or factory-vs-per-id) — overlapping
 * registration throws, so resolution is always unambiguous.
 *
 * Register everything at app launch *before* `Backgrounder.start()`. The
 * registry is sealed at `start()`; re-registering after that throws.
 *
 * `@OptIn(ExperimentalObjCName::class)`: Swift-rename annotations so iOS app
 * code calls `registry.register(taskId:factory:)` (CLAUDE.md §8).
 */
@OptIn(ExperimentalObjCName::class)
public class WorkerRegistry internal constructor() {
    // MUST NOT call suspend functions inside this block — see CLAUDE.md §3.
    private val lock = SynchronizedObject()
    private val factories: MutableMap<String, () -> BackgroundWorker> = mutableMapOf()
    private val factoryChain: MutableList<BackgroundWorkerFactory> = mutableListOf()
    private val sealed = atomic(false)

    /**
     * Associate [taskId] with a [factory] that builds a fresh [BackgroundWorker] per dispatch.
     *
     * Must be called before [Backgrounder.start]. Throws if the registry is already sealed or
     * [taskId] is already claimed by another per-id registration or a [BackgroundWorkerFactory].
     *
     * @throws IllegalStateException if the registry is sealed.
     * @throws IllegalArgumentException if [taskId] is already registered.
     */
    @ObjCName(swiftName = "register")
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    public fun register(
        taskId: String,
        factory: () -> BackgroundWorker,
    ): Unit =
        synchronized(lock) {
            checkNotSealed()
            requireValidTaskId(taskId)
            require(taskId !in factories) {
                "WorkerRegistry: task id '$taskId' is already registered."
            }
            val owningFactory = factoryChain.firstOrNull { taskId in it.taskIds }
            require(owningFactory == null) {
                "WorkerRegistry: task id '$taskId' is already claimed by a registered BackgroundWorkerFactory."
            }
            factories[taskId] = factory
        }

    /**
     * Register a [BackgroundWorkerFactory] that owns the ids in
     * [BackgroundWorkerFactory.taskIds].
     *
     * Must be called before [Backgrounder.start]. Throws if the registry is already sealed or
     * any of the factory's ids collide with a per-id registration or another factory.
     *
     * @throws IllegalStateException if the registry is sealed.
     * @throws IllegalArgumentException if any of [BackgroundWorkerFactory.taskIds] is already
     *   registered, or the factory declares no ids.
     */
    @ObjCName(swiftName = "register")
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    public fun register(factory: BackgroundWorkerFactory): Unit =
        synchronized(lock) {
            checkNotSealed()
            require(factory.taskIds.isNotEmpty()) {
                "WorkerRegistry: BackgroundWorkerFactory declares no task ids."
            }
            factory.taskIds.forEach { taskId ->
                requireValidTaskId(taskId)
                require(taskId !in factories) {
                    "WorkerRegistry: task id '$taskId' is already registered."
                }
                val owningFactory = factoryChain.firstOrNull { taskId in it.taskIds }
                require(owningFactory == null) {
                    "WorkerRegistry: task id '$taskId' is already claimed by a registered BackgroundWorkerFactory."
                }
            }
            factoryChain.add(factory)
        }

    /**
     * Returns the set of task ids currently registered — the union of per-id
     * registrations and every [BackgroundWorkerFactory]'s declared ids.
     * Snapshot — safe to call at any time.
     */
    @ObjCName(swiftName = "registeredIds")
    public fun registeredIds(): Set<String> =
        synchronized(lock) {
            buildSet {
                addAll(factories.keys)
                factoryChain.forEach { addAll(it.taskIds) }
            }
        }

    /**
     * Inspector-shaped view of every registered factory — one
     * [FactoryDescriptor.PerId] per closure registration, one
     * [FactoryDescriptor.Bulk] per [BackgroundWorkerFactory].
     *
     * Order is registration order: per-id registrations interleave naturally
     * with bulk factories in the order each appears here, but the public
     * surface presents per-ids first (sorted by task id for stability)
     * then bulk factories (in registration order) so the inspector output
     * stays deterministic across calls.
     */
    @ObjCName(swiftName = "factoryDescriptors")
    public fun factoryDescriptors(): List<FactoryDescriptor> =
        synchronized(lock) {
            buildList {
                factories.keys.sorted().forEach { id ->
                    add(FactoryDescriptor.PerId(taskId = id))
                }
                factoryChain.forEach { factory ->
                    add(FactoryDescriptor.Bulk(factoryId = factory.factoryId, taskIds = factory.taskIds.toSet()))
                }
            }
        }

    /**
     * Mark the registry sealed. After this, [register] throws — protects against
     * late registration after the platform has started dispatching work.
     */
    internal fun seal() {
        // Under the same lock as register(): a register racing start() either
        // lands before the seal or throws — it can never slip in after the
        // platform has begun installing OS handlers (see B-024).
        synchronized(lock) {
            sealed.value = true
        }
    }

    internal fun create(taskId: String): BackgroundWorker {
        // Resolve under the lock, invoke OUTSIDE it (B-021). Factories are user
        // code — running them while holding the registry lock serializes every
        // concurrent dispatch behind the slowest factory, and deadlocks if a
        // factory blocks on another thread that needs this lock.
        val perId = synchronized(lock) { factories[taskId] }
        if (perId != null) return perId()
        val owningFactory =
            synchronized(lock) {
                factoryChain.firstOrNull { taskId in it.taskIds }
                    ?: throw NoFactoryRegisteredException(taskId)
            }
        return owningFactory.create(taskId)
            ?: throw FactoryDeclinedException(taskId)
    }

    private fun checkNotSealed() {
        check(!sealed.value) {
            "WorkerRegistry has been sealed; register all workers before app launch completes."
        }
    }

    public class NoFactoryRegisteredException(
        public val taskId: String,
    ) : IllegalStateException("No BackgroundWorker factory registered for task id '$taskId'")

    /**
     * Thrown when a [BackgroundWorkerFactory] declares ownership of a [taskId]
     * in [BackgroundWorkerFactory.taskIds] but returns `null` from
     * [BackgroundWorkerFactory.create] for it — a violation of the factory's
     * sync contract.
     */
    public class FactoryDeclinedException(
        public val taskId: String,
    ) : IllegalStateException(
            "BackgroundWorkerFactory declared task id '$taskId' but create() returned null for it",
        )
}
