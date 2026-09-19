package com.happycodelucky.backgrounder

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Inspector-shaped view of one registered factory.
 *
 * Returned in a list by [BackgroundTaskManager.registeredFactories] so an inspector
 * UI can render "which factory owns which task id" without reaching into
 * [WorkerRegistry] directly. Two flavours map onto the two registration
 * shapes the registry supports:
 *
 *  - [PerId] — created from `BackgroundTaskManager.register(taskId, factory)`. One
 *    closure registered against one id; [factoryId] is `null` because
 *    closures have no name.
 *  - [Bulk] — created from `BackgroundTaskManager.register(factory: BackgroundWorkerFactory)`.
 *    A [BackgroundWorkerFactory] object owns many ids; [factoryId] mirrors
 *    [BackgroundWorkerFactory.factoryId] (which may itself be `null`).
 *
 * SKIE renders this as a Swift enum via `onEnum(of:)`; consumers exhaustively
 * `switch` on the two cases.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "FactoryDescriptor")
public sealed interface FactoryDescriptor {
    /** Optional human-readable identifier. `null` for per-id closures. */
    public val factoryId: String?

    /** Task ids owned by this factory. Always non-empty. */
    public val taskIds: Set<String>

    /** One closure registered against one task id. */
    public data class PerId(
        public val taskId: String,
    ) : FactoryDescriptor {
        public override val factoryId: String? get() = null
        public override val taskIds: Set<String> get() = setOf(taskId)
    }

    /** A [BackgroundWorkerFactory] object that owns one or more ids. */
    public data class Bulk(
        public override val factoryId: String?,
        public override val taskIds: Set<String>,
    ) : FactoryDescriptor
}
