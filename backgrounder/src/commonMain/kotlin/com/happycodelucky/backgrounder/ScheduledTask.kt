package com.happycodelucky.backgrounder

import kotlin.time.Instant

/**
 * A snapshot of one scheduled task's current state — returned from
 * [Scheduler.scheduled] for inspection.
 *
 * Best-effort per platform:
 * - Android: derived from `WorkInfo` directly.
 * - iOS: combined view of the library's state store and
 *   `BGTaskScheduler.getPendingTaskRequests`. The library does not directly
 *   observe a worker between handler-fire and `setTaskCompletedSuccess`, so
 *   that span is reported as [State.Pending] rather than [State.Running].
 */
public data class ScheduledTask(
    /** The task id this task was scheduled under. */
    public val taskId: String,
    /** Whether this task was scheduled as a one-time or repeating request. */
    public val kind: Kind,
    /** Current execution state; see [State] for per-platform accuracy caveats. */
    public val state: State,
    /** Best-effort hint of when the platform plans to run this next. May be null. */
    public val nextRunHint: Instant?,
    /** Library-tracked retry attempt counter (within a cycle for periodic). */
    public val attempt: Int,
    /** `true` if this task was scheduled with `WorkRequest.ephemeral = true`. */
    public val ephemeral: Boolean,
    /**
     * Conditions currently preventing this task from running, surfaced so
     * inspector UIs can answer "why isn't this dispatching yet?". Best-effort
     * per platform; see [PendingPredicate] for platform support caveats. Empty
     * when nothing is blocking dispatch.
     */
    public val pendingPredicates: List<PendingPredicate> = emptyList(),
) {
    public enum class Kind { OneTime, Periodic }

    public enum class State {
        /** Submitted to the platform; waiting for constraints / earliestBeginDate. */
        Pending,

        /** Currently executing (Android only — iOS reports as [Pending] until completion). */
        Running,

        /** Library is waiting on backoff before re-submitting. */
        Backoff,

        /** Awaiting prerequisite work (Android chained work; v2). */
        Blocked,
    }
}
