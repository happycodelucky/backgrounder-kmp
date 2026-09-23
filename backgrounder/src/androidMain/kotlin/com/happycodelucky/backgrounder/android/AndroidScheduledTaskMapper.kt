package com.happycodelucky.backgrounder.android

import androidx.work.NetworkType
import androidx.work.WorkInfo
import com.happycodelucky.backgrounder.NetworkRequirement
import com.happycodelucky.backgrounder.PendingPredicate
import com.happycodelucky.backgrounder.ScheduledTask
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pure mapping from a single WorkManager [WorkInfo] to a [ScheduledTask] snapshot.
 *
 * Extracted from [AndroidScheduledTaskQuery] so it can be unit-tested in
 * `androidHostTest` without standing up a full `WorkManagerTestInitHelper`
 * harness — `WorkInfo` is constructible from JVM tests, but
 * `WorkManager.getWorkInfosFlow` requires Robolectric. The query stays
 * the I/O-shaped layer; this mapper is the testable transform
 * (review-loop round 1, finding H-CONSENSUS-2).
 *
 * `state` mapping mirrors WorkManager's enum:
 * - `ENQUEUED` with `attempt > 0` → `Backoff`; `attempt == 0` → `Pending`.
 * - `RUNNING` → `Running`.
 * - `BLOCKED` → `Blocked` (Android chained work; v2 in this library).
 * - Terminal states (`SUCCEEDED`, `FAILED`, `CANCELLED`) should never arrive
 *   here — the query filters them out via `WorkQuery` — but if they do, we
 *   defensively map to `Pending` rather than throw.
 */
internal object AndroidScheduledTaskMapper {
    /**
     * Convert a [WorkInfo] to a [ScheduledTask], or return `null` if the
     * info doesn't carry the canonical Backgrounder task-id tag (e.g. the
     * user has unrelated work tagged into the same `WorkManager`).
     *
     * Production entry point — projects [WorkInfo] onto [WorkInfoView] and
     * delegates to [fromView]. Tests should call [fromView] directly with
     * a hand-built [WorkInfoView]; that avoids the verbose `WorkInfo`
     * constructor and lets the mapper be exercised on plain JVM without
     * Robolectric.
     *
     * @param info the WorkManager view of one scheduled item.
     * @param ephemeralIds the snapshot of currently-ephemeral task ids,
     *   used to set the [ScheduledTask.ephemeral] flag.
     */
    fun toScheduledTask(
        info: WorkInfo,
        ephemeralIds: Set<String>,
    ): ScheduledTask? = fromView(WorkInfoView.from(info), ephemeralIds)

    /**
     * Test-friendly mapper: pure function over the primitive fields of
     * [WorkInfo] that this class actually reads. Same logic as
     * [toScheduledTask], but the input is a plain data class so tests don't
     * need to construct a real `WorkInfo`.
     */
    internal fun fromView(
        view: WorkInfoView,
        ephemeralIds: Set<String>,
    ): ScheduledTask? {
        val taskIdString =
            view.tags
                .firstOrNull { it.startsWith(TASK_ID_TAG_PREFIX) }
                ?.removePrefix(TASK_ID_TAG_PREFIX)
                ?: return null
        val taskId = taskIdString
        val isPeriodic = view.tags.contains(KIND_PERIODIC_TAG)
        val nextRunHint =
            if (view.nextScheduleTimeMillis > 0L) {
                Instant.fromEpochMilliseconds(view.nextScheduleTimeMillis)
            } else {
                null
            }
        val state = mapState(view.state, view.runAttemptCount)
        return ScheduledTask(
            taskId = taskId,
            kind = if (isPeriodic) ScheduledTask.Kind.Periodic else ScheduledTask.Kind.OneTime,
            state = state,
            nextRunHint = nextRunHint,
            attempt = view.runAttemptCount,
            ephemeral = taskId in ephemeralIds,
            pendingPredicates = derivePredicates(view, state, nextRunHint),
        )
    }

    /**
     * Pure mapper from [WorkInfoView] (incl. its `constraintsView`) to the
     * list of predicates currently blocking dispatch.
     */
    private fun derivePredicates(
        view: WorkInfoView,
        state: ScheduledTask.State,
        nextRunHint: Instant?,
    ): List<PendingPredicate> {
        val result = mutableListOf<PendingPredicate>()
        val c = view.constraintsView
        if (c != null) {
            when (c.networkType) {
                NetworkType.NOT_REQUIRED -> {}

                NetworkType.CONNECTED -> {
                    result.add(PendingPredicate.NetworkRequired(NetworkRequirement.Any))
                }

                NetworkType.UNMETERED -> {
                    result.add(PendingPredicate.NetworkRequired(NetworkRequirement.Unmetered))
                }

                else -> {
                    // Other NetworkType values (NOT_ROAMING, METERED, TEMPORARILY_UNMETERED)
                    // aren't expressible via the cross-platform NetworkRequirement enum;
                    // surface them as "Any" rather than dropping silently.
                    result.add(PendingPredicate.NetworkRequired(NetworkRequirement.Any))
                }
            }
            if (c.requiresCharging) {
                result.add(PendingPredicate.RequiresCharging)
            }
            if (c.requiresDeviceIdle) {
                result.add(PendingPredicate.RequiresDeviceIdle)
            }
        }
        if (nextRunHint != null && nextRunHint > Clock.System.now()) {
            when (state) {
                ScheduledTask.State.Backoff -> {
                    result.add(PendingPredicate.WaitingForBackoff(until = nextRunHint))
                }

                ScheduledTask.State.Pending -> {
                    result.add(PendingPredicate.WaitingForEarliestBeginDate(at = nextRunHint))
                }

                ScheduledTask.State.Running,
                ScheduledTask.State.Blocked,
                -> {}
            }
        }
        return result
    }

    /**
     * Minimal projection of a [WorkInfo] — the four fields [fromView] reads.
     * Lets tests build inputs without constructing a real `WorkInfo`
     * (which has 13+ required constructor parameters and a constructor
     * shape that has shifted across androidx.work minor versions).
     */
    internal data class WorkInfoView(
        val tags: Set<String>,
        val state: WorkInfo.State,
        val runAttemptCount: Int,
        val nextScheduleTimeMillis: Long,
        /**
         * Projection of [WorkInfo.constraints] — only the two fields the
         * cross-platform [PendingPredicate] surface can represent. `null`
         * means the view came from a test that didn't supply constraint
         * info; the mapper omits constraint predicates rather than
         * defaulting to "no constraints" (which would emit a false negative).
         */
        val constraintsView: ConstraintsView? = null,
    ) {
        companion object {
            fun from(info: WorkInfo): WorkInfoView =
                WorkInfoView(
                    tags = info.tags,
                    state = info.state,
                    runAttemptCount = info.runAttemptCount,
                    nextScheduleTimeMillis = info.nextScheduleTimeMillis,
                    constraintsView =
                        ConstraintsView(
                            networkType = info.constraints.requiredNetworkType,
                            requiresCharging = info.constraints.requiresCharging(),
                            requiresDeviceIdle = info.constraints.requiresDeviceIdle(),
                        ),
                )
        }
    }

    /** Constraints projection — see [WorkInfoView.constraintsView]. */
    internal data class ConstraintsView(
        val networkType: NetworkType,
        val requiresCharging: Boolean,
        val requiresDeviceIdle: Boolean,
    )

    private fun mapState(
        state: WorkInfo.State,
        attempt: Int,
    ): ScheduledTask.State =
        when (state) {
            WorkInfo.State.ENQUEUED -> if (attempt > 0) ScheduledTask.State.Backoff else ScheduledTask.State.Pending

            WorkInfo.State.RUNNING -> ScheduledTask.State.Running

            WorkInfo.State.BLOCKED -> ScheduledTask.State.Blocked

            WorkInfo.State.SUCCEEDED,
            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED,
            -> ScheduledTask.State.Pending
        }

    /** The canonical tag every Backgrounder request carries. */
    internal const val BACKGROUNDER_TAG: String = "_backgrounder"

    /** Tag prefix carrying the encoded task id. */
    internal const val TASK_ID_TAG_PREFIX: String = "_backgrounder.id="

    /** Tag stamped on Periodic requests. */
    internal const val KIND_PERIODIC_TAG: String = "_backgrounder.kind=periodic"
}
