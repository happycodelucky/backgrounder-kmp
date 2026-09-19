package com.happycodelucky.backgrounder

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Instant

/**
 * A single condition currently preventing a scheduled task from running.
 *
 * Surfaced on [ScheduledTask.pendingPredicates] — operators reading
 * [BackgroundTaskManager.scheduled] can see *why* a task isn't running yet, not just
 * its [ScheduledTask.State]. Multiple predicates can apply at once
 * (e.g. unmet network + in-backoff).
 *
 * Best-effort per platform — some predicates are observable from one
 * platform's state model but not another's. The list is empty when no
 * predicate is actively blocking dispatch.
 *
 * SKIE renders this as a Swift `enum` for exhaustive `switch` via
 * `onEnum(of:)`.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "PendingPredicate")
public sealed interface PendingPredicate {
    /**
     * The configured [WorkConstraints.networkRequired] is not currently
     * satisfied. The library will gate worker entry on reachability up to
     * the per-platform timeout (see [DeferralReason.ReachabilityTimeout]).
     */
    public data class NetworkRequired(
        public val requirement: NetworkRequirement,
    ) : PendingPredicate

    /**
     * The configured [WorkConstraints.requiresCharging] is set and the
     * device is not currently on external power.
     *
     * **Platform support.** Android honours this via `WorkInfo.constraints`
     * read-back. iOS does not currently persist the charging requirement in
     * the state store — surfacing this predicate there is a v2 follow-up
     * gated on a state-store schema bump. macOS does not enforce charging
     * via `NSBackgroundActivityScheduler` at all.
     */
    public data object RequiresCharging : PendingPredicate

    /**
     * The configured [WorkConstraints.requiresDeviceIdle] is set and the device
     * is not currently idle (not in a Doze maintenance window).
     *
     * **Platform support.** Android honours this via `WorkInfo.constraints`
     * read-back — the OS enforces idle-state gating at the `JobScheduler` level.
     * iOS has no `BGProcessingTaskRequest` idle knob; this predicate is never
     * surfaced on iOS. macOS and JVM have no idle primitive and do not surface
     * this predicate.
     */
    public data object RequiresDeviceIdle : PendingPredicate

    /**
     * The task is in a backoff window — the library is delaying the next
     * attempt to honour the configured [BackoffPolicy]. [until] is the
     * library's best-effort estimate of when the backoff will release.
     */
    public data class WaitingForBackoff(
        public val until: Instant?,
    ) : PendingPredicate

    /**
     * The OS scheduler has accepted the request but its earliest-begin
     * window has not yet elapsed. On iOS this maps to
     * `BGTaskRequest.earliestBeginDate`; on Android, to
     * `WorkInfo.nextScheduleTimeMillis` when set in the future.
     */
    public data class WaitingForEarliestBeginDate(
        public val at: Instant?,
    ) : PendingPredicate
}
