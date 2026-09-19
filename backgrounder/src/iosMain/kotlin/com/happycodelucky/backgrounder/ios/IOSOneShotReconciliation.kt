package com.happycodelucky.backgrounder.ios

import co.touchlab.kermit.Logger
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler

/**
 * Launch-time cleanup for one-shots whose run died with a previous process.
 *
 * Process-death contract (D-020/D-022): a run interrupted by process death is
 * dead — its transaction state must die with it. When a one-shot's `BGTask`
 * fires, iOS consumes the pending request; if the process then dies
 * mid-execution, nothing will ever fire for that id again, but the state
 * store still says `active = true`. Left alone, that ghost blocks every
 * future `ConflictPolicy.Keep` schedule for the id and haunts `scheduled()`.
 *
 * Reconciliation: any known one-shot that claims to be active but has **no**
 * pending OS request is dead — clear it. One-shots that *do* have a pending
 * request were scheduled-but-never-started: they are not interrupted, will
 * fire later, and may complete normally (including backoff resubmits, which
 * always have a fresh pending request).
 *
 * **Snapshot-first ordering.** [launch]'s candidate list is captured *before*
 * the asynchronous OS query is issued, so a task scheduled after `start()`
 * can never be swept by a late-arriving callback — it isn't in the snapshot.
 *
 * Periodics are untouched: post step-6 cut-over they have no per-id OS
 * request to reconcile against, and [BGTaskHandlerRegistration]'s
 * resurrection path owns their launch-time lifecycle.
 */
internal class IOSOneShotReconciliation(
    private val state: IOSStateStore,
    private val mutexes: IOSTaskMutexes,
) {
    private val log = Logger.withTag("Backgrounder/iOS/Reconcile")

    /** Query the OS and clear dead one-shots. Called once from `start()`. */
    fun launch() {
        val candidates = snapshotCandidates()
        if (candidates.isEmpty()) return
        BGTaskScheduler.sharedScheduler.getPendingTaskRequestsWithCompletionHandler { list ->
            val pending =
                list
                    .orEmpty()
                    .filterIsInstance<BGTaskRequest>()
                    .mapTo(mutableSetOf()) { it.identifier }
            apply(candidates, pending)
        }
    }

    /** Active one-shots known to the store at this instant. */
    internal fun snapshotCandidates(): List<String> =
        state
            .knownTaskIds()
            .filter { state.readKind(it) == IOSStateStore.Kind.OneShot && state.readActive(it) }

    /**
     * Clear every [candidates] entry with no pending OS request. Split from
     * [launch] so tests can drive it with a fake pending set — the real
     * `BGTaskScheduler` query needs a live app host.
     */
    internal fun apply(
        candidates: List<String>,
        pendingIdentifiers: Set<String>,
    ) {
        candidates
            .filterNot { it in pendingIdentifiers }
            .forEach { id ->
                log.i { "one-shot $id died with a previous process (no pending OS request); clearing its state" }
                state.clear(id)
                mutexes.forget(id)
            }
    }
}
