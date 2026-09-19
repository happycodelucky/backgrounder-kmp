package com.happycodelucky.backgrounder

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * A user-supplied factory that builds [BackgroundWorker]s for a *set* of
 * task ids, resolving the concrete worker lazily at dispatch time.
 *
 * This is the bulk alternative to per-id registration
 * ([WorkerRegistry.register] / [Backgrounder.register] taking a single
 * task id + closure). Register one factory that owns many ids — typically
 * one factory per app module, closing over that module's DI graph.
 *
 * **The [taskIds] / [create] sync contract.** [taskIds] must enumerate
 * *every* id [create] is able to build. The library leans on [taskIds] at
 * `start()` to register OS-level handlers — on iOS each id is handed to
 * `BGTaskScheduler.register(forTaskWithIdentifier:)`, and every id there must
 * also appear in the app's `BGTaskSchedulerPermittedIdentifiers` Info.plist
 * array. An id that [create] can build but that is missing from [taskIds]
 * will never get an OS handler and its scheduled work will silently never
 * fire. Keeping the two in sync is the client's responsibility.
 *
 * Conversely, if [create] is called with an id that *is* in [taskIds] but
 * returns `null`, that's treated as a client bug — the registry throws
 * [WorkerRegistry.FactoryDeclinedException] rather than falling through.
 * `create` returning `null` is only legitimate for an id the factory does
 * not own (the registry never calls it for such ids, but the nullable return
 * keeps the contract explicit and chain-friendly).
 *
 * Resolution order when multiple registrations exist: exact per-id
 * registrations win first, then factories in registration order. Declared
 * id sets must not overlap with each other or with per-id registrations —
 * the registry rejects overlapping registration to keep resolution
 * unambiguous.
 *
 * `@OptIn(ExperimentalObjCName::class)`: Swift-rename annotation for boundary
 * refinement. Stable in practice and required by SKIE (CLAUDE.md §8).
 */
@OptIn(ExperimentalObjCName::class)
public interface BackgroundWorkerFactory {
    /**
     * Optional human-readable identifier surfaced via
     * [Backgrounder.registeredFactories]. Useful in inspector dashboards
     * for attributing task ids to the owning module / DI scope when one
     * factory manages many ids.
     *
     * Default `null` is shown as `"<anonymous>"` by the inspector — no
     * existing implementations need to be updated.
     */
    @ObjCName(swiftName = "factoryId")
    public val factoryId: String? get() = null

    /**
     * Every task id this factory can build a worker for. Must stay in sync
     * with [create] — see the type-level KDoc for the contract.
     */
    @ObjCName(swiftName = "taskIds")
    public val taskIds: Set<String>

    /**
     * Build a fresh [BackgroundWorker] for [taskId], or `null` if this
     * factory does not own [taskId].
     *
     * Called afresh on every dispatch — never cache the returned worker.
     * Returning `null` for a [taskId] that is in [taskIds] is a client error
     * and surfaces as [WorkerRegistry.FactoryDeclinedException].
     */
    @ObjCName(swiftName = "create")
    public fun create(taskId: String): BackgroundWorker?
}
