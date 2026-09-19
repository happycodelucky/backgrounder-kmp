package com.happycodelucky.backgrounder.ios

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-task id [Mutex] map.
 *
 * Concurrency in iOS scheduling: BGTaskScheduler may fire two distinct task
 * identifiers concurrently, and the user's code can call
 * `Scheduler.schedule` for one task id while another instance's handler is
 * mid-flight. Every read-modify-write of `tasks.<id>.*` is wrapped in
 * `withMutex(taskId)` so we don't lose a state transition to a race.
 */
internal class IOSTaskMutexes {
    // Two-tier locking: this `SynchronizedObject` guards the *map* itself
    // (insert/remove of `Mutex` entries) for short, non-suspending sections.
    // The per-task `Mutex` below is the suspend-aware lock held across user
    // worker execution. See CLAUDE.md §3.
    // MUST NOT call suspend functions inside this synchronized() block.
    private val lock = SynchronizedObject()
    private val mutexes: MutableMap<String, Mutex> = mutableMapOf()

    suspend inline fun <T> withMutex(
        taskId: String,
        crossinline block: suspend () -> T,
    ): T = mutexFor(taskId).withLock { block() }

    fun mutexFor(taskId: String): Mutex =
        synchronized(lock) {
            mutexes.getOrPut(taskId) { Mutex() }
        }

    /**
     * Drop the [Mutex] for [taskId]. Call from terminal lifecycle points (cancel,
     * cancelAll, one-shot success/failure/give-up) so the map doesn't grow without
     * bound across many distinct task ids over a long-running process.
     *
     * Safe to call when no entry exists — it's a no-op.
     */
    fun forget(taskId: String) {
        synchronized(lock) {
            mutexes.remove(taskId)
        }
    }

    /**
     * Drop every entry. Pairs with `cancelAll`.
     */
    fun forgetAll() {
        synchronized(lock) {
            mutexes.clear()
        }
    }

    /** Test seam — number of currently-tracked task ids. */
    internal fun trackedCount(): Int = synchronized(lock) { mutexes.size }
}
