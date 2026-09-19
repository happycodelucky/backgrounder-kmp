package com.happycodelucky.backgrounder.android

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Process-local set of task ids the [WorkManagerScheduler] has scheduled in
 * this process. Used to drive the [com.happycodelucky.backgrounder.CancelOutcome] returned
 * by `cancel(id)` / `cancelAll()`: WorkManager's `cancelUniqueWork` is
 * fire-and-forget and gives us no way to tell whether the id had any
 * pending/running work, so we'd otherwise have to lie and always report
 * `Cancelled(1)` (review-loop round 1, finding H-CONSENSUS-1).
 *
 * Best-effort across processes: if a sibling process scheduled the work and
 * this process didn't, we report `NoSuchTask` even though the work was real.
 * Matches the macOS scheduler's in-process tracking model and the iOS
 * behaviour when the state store hasn't seen the task. Better than always
 * claiming success.
 *
 * Concurrency: guarded by [SynchronizedObject]/[synchronized] (CLAUDE.md §3
 * two-tier pattern — non-suspending critical section, no `kotlin.synchronized`).
 */
internal class ScheduledIdsTracker {
    private val lock = SynchronizedObject()
    private val ids: MutableSet<String> = mutableSetOf()
    // MUST NOT call suspend functions inside this block.

    /** Record that [taskId] was just scheduled in this process. */
    fun add(taskId: String) {
        synchronized(lock) { ids.add(taskId) }
    }

    /**
     * Record that [taskId] was just scheduled, returning whether it was
     * *already* tracked. One guarded read-modify-write so the caller's
     * "emit `ScheduleReplaced`?" decision can't race a concurrent
     * `schedule()` — a snapshot-then-add pair lets two racing callers both
     * miss (or both see) the prior entry.
     */
    fun addAndWasPresent(taskId: String): Boolean = synchronized(lock) { !ids.add(taskId) }

    /**
     * Remove [taskId] if it was tracked. Returns `true` if it was previously
     * tracked (callers map this to `Cancelled(1)`), `false` otherwise (callers
     * map to `NoSuchTask`).
     */
    fun removeIfPresent(taskId: String): Boolean = synchronized(lock) { ids.remove(taskId) }

    /** Clear and return the count of ids that were tracked. */
    fun clearAndCount(): Int =
        synchronized(lock) {
            val n = ids.size
            ids.clear()
            n
        }

    /** Snapshot for tests / diagnostics. */
    fun snapshot(): Set<String> = synchronized(lock) { ids.toSet() }
}
