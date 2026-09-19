// ExperimentalForeignApi: required for cinterop FFI types. Stable in practice.
@file:OptIn(ExperimentalForeignApi::class)

package com.happycodelucky.backgrounder.ios

import com.happycodelucky.backgrounder.NetworkRequirement
import com.happycodelucky.backgrounder.PendingPredicate
import com.happycodelucky.backgrounder.ScheduledTask
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Cross-references the persistent [IOSStateStore] with iOS's
 * `BGTaskScheduler.getPendingTaskRequests` to produce [ScheduledTask]
 * snapshots. Best-effort — see plan §iOS step 6 for the state mapping.
 */
internal class IOSScheduledTaskQuery(
    private val state: IOSStateStore,
) {
    suspend fun snapshot(): List<ScheduledTask> {
        val pending: Map<String, BGTaskRequest> = pendingByIdentifier()
        return state.knownTaskIds().mapNotNull { id ->
            val active = state.readActive(id)
            if (!active) return@mapNotNull null
            val kind =
                when (state.readKind(id)) {
                    IOSStateStore.Kind.OneShot -> ScheduledTask.Kind.OneTime
                    IOSStateStore.Kind.Periodic -> ScheduledTask.Kind.Periodic
                    null -> return@mapNotNull null
                }
            val attempt = state.readAttempt(id)
            val osPending = pending.containsKey(id)
            val state0 =
                when {
                    osPending -> ScheduledTask.State.Pending
                    attempt > 0 -> ScheduledTask.State.Backoff
                    else -> ScheduledTask.State.Blocked
                }
            val nextRunMs = state.readNextRunEpochMs(id)
            val nextRunHint = nextRunMs?.let(Instant::fromEpochMilliseconds)
            ScheduledTask(
                taskId = id,
                kind = kind,
                state = state0,
                nextRunHint = nextRunHint,
                attempt = attempt,
                ephemeral = state.readEphemeral(id),
                pendingPredicates = derivePredicates(id, state0, nextRunHint, attempt),
            )
        }
    }

    /**
     * Derive the predicates currently blocking dispatch. iOS knows:
     *  - the persisted `networkRequired` from [IOSStateStore];
     *  - the persisted `nextRunEpochMs`, which is either the OS-side
     *    `earliestBeginDate` (Pending state) or a backoff horizon
     *    (Backoff state).
     *
     * `requiresCharging` is not currently persisted in [IOSStateStore];
     * surfacing it is a v2 follow-up gated on a schema bump.
     */
    private fun derivePredicates(
        taskId: String,
        state0: ScheduledTask.State,
        nextRunHint: Instant?,
        @Suppress("UNUSED_PARAMETER") attempt: Int,
    ): List<PendingPredicate> {
        val result = mutableListOf<PendingPredicate>()
        val req = state.readNetworkRequired(taskId)
        if (req != NetworkRequirement.None) {
            result.add(PendingPredicate.NetworkRequired(requirement = req))
        }
        if (nextRunHint != null && nextRunHint > Clock.System.now()) {
            when (state0) {
                ScheduledTask.State.Backoff -> {
                    result.add(PendingPredicate.WaitingForBackoff(until = nextRunHint))
                }

                ScheduledTask.State.Pending -> {
                    result.add(PendingPredicate.WaitingForEarliestBeginDate(at = nextRunHint))
                }

                ScheduledTask.State.Running,
                ScheduledTask.State.Blocked,
                -> {
                    Unit
                }
            }
        }
        return result
    }

    // `suspendCoroutine`, not `suspendCancellableCoroutine`: the OS call cannot
    // be cancelled in flight, and the callback always arrives promptly — a
    // brief non-cancellable wait states the truth, instead of relying on
    // "resume after cancellation is a silent no-op" (an implementation detail
    // of kotlinx.coroutines, not a contract). Caller cancellation is processed
    // at the next suspension point after resume.
    private suspend fun pendingByIdentifier(): Map<String, BGTaskRequest> =
        suspendCoroutine { cont ->
            BGTaskScheduler.sharedScheduler.getPendingTaskRequestsWithCompletionHandler { list ->
                // Defensive: K/N stubs the callback as `List<*>?` and the element
                // type isn't checked until access. filterIsInstance drops any
                // stray non-BGTaskRequest entry rather than crashing later.
                val requests = list.orEmpty().filterIsInstance<BGTaskRequest>()
                cont.resume(requests.associateBy { it.identifier })
            }
        }
}
