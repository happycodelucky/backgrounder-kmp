package com.happycodelucky.backgrounder

import kotlin.experimental.ExperimentalObjCName
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlin.native.ObjCName
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Sealed event stream emitted by the library for instrumentation / inspection.
 *
 * Every internal scheduling, dispatch, deferral, completion, and library-level
 * error path produces one or more [MonitorEvent]s. Consumers observe the stream
 * through [Backgrounder.events] as a `SharedFlow<MonitorEvent>` (Swift sees it
 * as `AsyncSequence<MonitorEvent>` via SKIE), or — for the imperative
 * callback style — implement [BackgrounderEventListener]. Both delivery
 * mechanisms are fed from the same emit point and receive the same events.
 *
 * **Delivery guarantees.**
 *  - **Synchronous to the producer.** [BackgrounderEventListener] callbacks
 *    run inline on the dispatcher thread. Implementations must not block.
 *  - **`tryEmit` on the flow.** Emit into the shared flow is non-suspending —
 *    a slow collector cannot pin scheduler dispatch (CLAUDE.md §3). The
 *    backing flow uses `replay = 0`, `extraBufferCapacity = 64`,
 *    `BufferOverflow.DROP_OLDEST`. Late-attached collectors do not see
 *    historical events; long stalls drop the oldest unread events first.
 *  - **Best-effort ordering per `taskId`.** Within one task id the natural
 *    order is preserved (scheduled → started → completed → cancelled).
 *    Cross-task ordering follows the producer's interleave.
 *
 * **Swift bridging.** SKIE renders this sealed interface as a Swift `enum`
 * usable with `onEnum(of:)` for exhaustive `switch`. The payload sealed types
 * ([CancelSource], [DeferralReason], [SkipReason], [AttemptFailureReason]) are
 * intentionally top-level — nested sealed types inside a sealed parent bridge
 * unreliably (LESSONS.md D-004).
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "MonitorEvent")
public sealed interface MonitorEvent {
    /** The task this event belongs to. */
    public val taskId: TaskId

    /** Wall-clock time the event was emitted. */
    public val at: Instant

    /**
     * A new request was accepted by the scheduler. Fires for both fresh
     * registrations and Replace-policy overwrites; for Replace, [ScheduleReplaced]
     * is also emitted with the previous request.
     */
    public data class Scheduled(
        public override val taskId: TaskId,
        public override val at: Instant,
        public val request: WorkRequest,
    ) : MonitorEvent

    /**
     * A pre-existing scheduled request for this id was displaced by a new one.
     * Emitted alongside (and immediately before) a fresh [Scheduled].
     * Not emitted when [policy] is [ConflictPolicy.Keep] and the existing
     * request wins the race — that path is silent (the user-supplied request
     * had no observable effect).
     */
    public data class ScheduleReplaced(
        public override val taskId: TaskId,
        public override val at: Instant,
        public val policy: ConflictPolicy,
        public val current: WorkRequest,
    ) : MonitorEvent

    /**
     * The task was removed. [source] discriminates user-initiated cancellation
     * from library-internal teardown (shutdown) and replacement-triggered
     * cancellation.
     */
    public data class Cancelled(
        public override val taskId: TaskId,
        public override val at: Instant,
        public val source: CancelSource,
    ) : MonitorEvent

    /**
     * The platform fired the worker and execution is about to begin.
     *
     * @param attempt 0-based retry counter; 0 on the first invocation.
     * @param expectedAt the time the task was *supposed* to start, if the
     *   library has a hint. May be `null` for platforms where the OS does not
     *   surface the scheduled run time (iOS `BGTaskScheduler`).
     */
    public data class WorkStarted(
        public override val taskId: TaskId,
        public override val at: Instant,
        public val attempt: Int,
        public val expectedAt: Instant?,
    ) : MonitorEvent

    /**
     * The worker's [BackgroundWorker.execute] returned (regardless of
     * [WorkResult]). [runtime] is wall-clock duration measured from the
     * paired [WorkStarted].
     */
    public data class WorkCompleted(
        public override val taskId: TaskId,
        public override val at: Instant,
        public val attempt: Int,
        public val result: WorkResult,
        public val runtime: Duration,
    ) : MonitorEvent

    /**
     * The platform fired the worker but the library deferred execution because
     * a predicate was not met. Followed (after the library converts to retry)
     * by a [WorkCompleted] with [WorkResult.Retry]. See [DeferralReason] for
     * the discriminator.
     */
    public data class AttemptDeferred(
        public override val taskId: TaskId,
        public override val at: Instant,
        public val attempt: Int,
        public val reason: DeferralReason,
    ) : MonitorEvent

    /**
     * The library would have run the worker but skipped entirely — for
     * structural reasons that prevent any future attempt from succeeding
     * (no factory, declined factory, ephemeral wash). No retry follows.
     */
    public data class Skipped(
        public override val taskId: TaskId,
        public override val at: Instant,
        public val reason: SkipReason,
    ) : MonitorEvent

    /**
     * An attempt failed in a way that is not the worker returning
     * [WorkResult.Failure]: the OS expired the task, the factory threw, the
     * worker threw an uncaught exception. The library converts these to
     * [WorkResult.Retry] (or [WorkResult.Failure] on max-attempts exhaustion)
     * internally; this event surfaces the original cause.
     */
    public data class AttemptFailed(
        public override val taskId: TaskId,
        public override val at: Instant,
        public val attempt: Int,
        public val reason: AttemptFailureReason,
    ) : MonitorEvent

    /**
     * The library scheduled a retry for [nextAttempt] after a failed attempt.
     * Emitted just before resubmitting to the platform.
     */
    public data class RetryScheduled(
        public override val taskId: TaskId,
        public override val at: Instant,
        public val nextAttempt: Int,
        public val delay: Duration,
        public val nextRunHint: Instant?,
    ) : MonitorEvent

    /**
     * Library-internal error that does not fit the per-attempt frame —
     * for example `BGTaskScheduler.submit` rejecting a request, or a
     * platform-level callback throwing. The library has already handled the
     * error (logged, possibly rejected the schedule); this event surfaces
     * what would otherwise be Kermit-only.
     */
    public data class LibraryError(
        public override val taskId: TaskId,
        public override val at: Instant,
        public val message: String,
        /**
         * Raw cause for Kotlin consumers. Hidden from ObjC — `Throwable`
         * bridges to Swift as an opaque `KotlinThrowable` with no structure
         * (CLAUDE.md §8, N-009 root cause). Swift reads [causeMessage] /
         * [causeType] instead.
         *
         * `@OptIn(ExperimentalObjCRefinement::class)`: standard SKIE-recognised
         * annotation; stable in practice.
         */
        @OptIn(ExperimentalObjCRefinement::class)
        @HiddenFromObjC
        public val cause: Throwable?,
        /** [cause]'s message, bridged for Swift. */
        public val causeMessage: String? = cause?.message,
        /** [cause]'s concrete class simple name (e.g. `"SerializationException"`), bridged for Swift. */
        public val causeType: String? = cause?.let { it::class.simpleName },
    ) : MonitorEvent
}

/** Why a [MonitorEvent.Cancelled] was emitted. */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "CancelSource")
public sealed interface CancelSource {
    /** A direct caller invoked [Backgrounder.cancel] or [Backgrounder.cancelAll]. */
    public data object User : CancelSource

    /** A new schedule with [ConflictPolicy.Replace] displaced this task. */
    public data object Replaced : CancelSource

    /** Library shutdown cancelled this task as part of teardown. */
    public data object Shutdown : CancelSource
}

/** Why a [MonitorEvent.AttemptDeferred] was emitted. */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "DeferralReason")
public sealed interface DeferralReason {
    /**
     * The reachability gate timed out waiting for the configured
     * [WorkConstraints.networkRequired] to be satisfied.
     *
     * [waited] is the effective wait window the gate held the dispatch for
     * before giving up — `min(5.seconds, maxExecutionTime / 4)`, the same
     * value the platform docs quote. It is the real hold time, **not** the
     * raw per-invocation execution budget.
     */
    public data class ReachabilityTimeout(
        public val requirement: NetworkRequirement,
        public val waited: Duration,
    ) : DeferralReason

    /** A periodic tick fired but no task was due to run. */
    public data object NoMatchingTick : DeferralReason

    /** The configured charging requirement was not satisfied. */
    public data object ChargingNotMet : DeferralReason

    /**
     * The task is in a backoff window — the library asked the OS to delay
     * until [until], but the OS fired early.
     */
    public data class BackoffWindow(
        public val until: Instant?,
    ) : DeferralReason
}

/** Why a [MonitorEvent.Skipped] was emitted — structurally unrecoverable. */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "SkipReason")
public sealed interface SkipReason {
    /** No factory was registered for this task id when the OS fired it. */
    public data object NotRegistered : SkipReason

    /**
     * The task was marked `ephemeral` and was washed away by the
     * library's ephemeral sweep before it had a chance to run.
     */
    public data object EphemeralExpired : SkipReason

    /**
     * A registered [BackgroundWorkerFactory] returned `null` from
     * [BackgroundWorkerFactory.create] despite declaring [taskId] in its
     * [BackgroundWorkerFactory.taskIds]. Client bug — see
     * [WorkerRegistry.FactoryDeclinedException].
     */
    public data object FactoryDeclined : SkipReason
}

/** Why a [MonitorEvent.AttemptFailed] was emitted. */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "AttemptFailureReason")
public sealed interface AttemptFailureReason {
    /** The OS reclaimed the task (iOS expiration handler fired). */
    public data object ExpiredByOS : AttemptFailureReason

    /** The factory threw while creating the worker. */
    public data class FactoryThrew(
        /**
         * Raw cause for Kotlin consumers. Hidden from ObjC — `Throwable`
         * bridges to Swift as an opaque `KotlinThrowable` (CLAUDE.md §8).
         * Swift reads [causeMessage] / [causeType] instead.
         *
         * `@OptIn(ExperimentalObjCRefinement::class)`: standard SKIE-recognised
         * annotation; stable in practice.
         */
        @OptIn(ExperimentalObjCRefinement::class)
        @HiddenFromObjC
        public val cause: Throwable,
        /** [cause]'s message, bridged for Swift. */
        public val causeMessage: String? = cause.message,
        /** [cause]'s concrete class simple name, bridged for Swift. */
        public val causeType: String? = cause::class.simpleName,
    ) : AttemptFailureReason

    /** [BackgroundWorker.execute] threw an uncaught exception. */
    public data class WorkerThrew(
        /**
         * Raw cause for Kotlin consumers. Hidden from ObjC — `Throwable`
         * bridges to Swift as an opaque `KotlinThrowable` (CLAUDE.md §8).
         * Swift reads [causeMessage] / [causeType] instead.
         *
         * `@OptIn(ExperimentalObjCRefinement::class)`: standard SKIE-recognised
         * annotation; stable in practice.
         */
        @OptIn(ExperimentalObjCRefinement::class)
        @HiddenFromObjC
        public val cause: Throwable,
        /** [cause]'s message, bridged for Swift. */
        public val causeMessage: String? = cause.message,
        /** [cause]'s concrete class simple name, bridged for Swift. */
        public val causeType: String? = cause::class.simpleName,
    ) : AttemptFailureReason
}
