package com.happycodelucky.backgrounder

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/**
 * In-process registry of in-flight `runNow` invocations, keyed by task id.
 *
 * Single-slot per task id: at most one [Entry] exists per id at any time. This
 * is safe because [BackgroundTaskManager.runNow] enforces pre-emption (a new `runNow`
 * for the same task id cancels the previous one before submitting), and
 * [InstantRunner.cancelInFlight] is the only other path that mutates the slot.
 *
 * Each [Entry] is a single-cell handle: the `runNow` caller registers the
 * entry, submits the platform request, then awaits [Entry.deferred]. The
 * platform handler (Android `InstantDispatchWorker`, iOS `IOSCoroutineBridge`,
 * macOS / JVM launched coroutine) [take]s the entry, runs the `task` lambda,
 * completes the deferred, and the caller resumes. Cancellation paths complete
 * the deferred with [kotlin.coroutines.cancellation.CancellationException].
 *
 * Locking rules (CLAUDE.md §3):
 * - The internal lock is a non-suspending [SynchronizedObject].
 * - **MUST NOT** call `suspend` functions inside the lock. The
 *   [CompletableDeferred] is *completed* outside the lock — [put], [take],
 *   [peek], and [remove] only mutate the map.
 */
internal class PendingInstantCalls {
    /**
     * One in-flight `runNow` invocation. Entries are unique per task id; the
     * type-erased `Any` payload of [deferred] is safe because each entry is
     * created and consumed inside a single generic `runNow<R>` call frame —
     * the `R` type is never lost across boundaries.
     */
    internal class Entry(
        val taskId: String,
        val task: suspend () -> Any?,
        val deferred: CompletableDeferred<Any?>,
    ) {
        // `kotlinx.atomicfu.atomic` rather than `@Volatile`: the JVM @Volatile
        // annotation does not exist on Kotlin/Native, and CLAUDE.md §3 forbids
        // volatile across the codebase. atomicfu lowers to the right primitive
        // on each target.
        private val jobRef = atomic<Job?>(null)
        private val platformHandleRef = atomic<Any?>(null)

        /**
         * The platform-side job that runs [task]. Set by the platform handler
         * once it has launched the worker. Used by `cancelInFlight`-style
         * paths to interrupt an executing lambda.
         *
         * Non-`null` only between platform-handler-launch and lambda-completion.
         */
        var job: Job?
            get() = jobRef.value
            set(value) {
                jobRef.value = value
            }

        /**
         * Optional platform handle for cancelling the OS-level request
         * (Android: `WorkManager` `workId` as `String`; iOS: the
         * `BGTaskRequest` identifier; macOS: nothing). Stored opaquely so the
         * platform layer can carry whatever it needs without leaking platform
         * types into [PendingInstantCalls].
         */
        var platformHandle: Any?
            get() = platformHandleRef.value
            set(value) {
                platformHandleRef.value = value
            }
    }

    // MUST NOT call suspend functions inside this block — see CLAUDE.md §3.
    private val lock = SynchronizedObject()
    private val slots: MutableMap<String, Entry> = mutableMapOf()

    /**
     * Insert [entry] for `entry.taskId`, returning any prior entry that was
     * displaced. The caller is responsible for cancelling the displaced
     * entry's deferred *outside* the lock.
     */
    fun put(entry: Entry): Entry? =
        synchronized(lock) {
            slots.put(entry.taskId, entry)
        }

    /**
     * Remove and return the entry for [taskId], or `null` if none.
     * Used by platform handlers when the OS dispatches the work.
     */
    fun take(taskId: String): Entry? =
        synchronized(lock) {
            slots.remove(taskId)
        }

    /**
     * Read the entry for [taskId] without removing it. Used by handler code
     * that wants to inspect the entry before deciding to take it.
     */
    fun peek(taskId: String): Entry? =
        synchronized(lock) {
            slots[taskId]
        }

    /**
     * Remove the entry for [taskId] iff it is the same instance as [entry].
     * Used by `runNow`'s `try/finally` cleanup so a late-arriving
     * pre-emption-already-replaced-us entry isn't wiped by accident.
     *
     * @return `true` if the entry was removed.
     */
    fun removeIfSame(
        taskId: String,
        entry: Entry,
    ): Boolean =
        synchronized(lock) {
            if (slots[taskId] === entry) {
                slots.remove(taskId)
                true
            } else {
                false
            }
        }
}
