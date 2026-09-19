package com.happycodelucky.backgrounder

/**
 * Internal abstraction backing [BackgroundTaskManager.runNow]. **Not part of [Scheduler]**
 * — instant runs intentionally bypass the scheduling pipeline (no
 * [WorkConstraints], no [BackoffPolicy], no [ExecutionHint]).
 *
 * Per-platform actuals:
 *  - Android: `WorkManagerInstantRunner` (one-shot `OneTimeWorkRequest` keyed by
 *    `taskId`, dispatched via the standalone `InstantDispatchWorker`).
 *  - iOS: `BGTaskInstantRunner` (one-shot `BGProcessingTaskRequest`, routed
 *    through a parallel branch in `IOSCoroutineBridge`).
 *  - macOS / JVM: [LibraryScopeInstantRunner] (no platform scheduler — runs on
 *    a library-owned `SupervisorJob` scope; shared implementation in
 *    `commonMain`).
 *
 * **Pre-emption invariant.** The contract — enforced by [BackgroundTaskManager.runNow] —
 * is that calls for the same `taskId` are *last-wins*: each new call cancels
 * the previous in-flight call's `Deferred<R>` (so the previous caller's
 * `await` rethrows `CancellationException`). As a consequence, an
 * implementation only ever holds *one* in-flight entry per `taskId` at a time
 * and may use a single-slot map keyed by task id rather than a queue.
 *
 * **Cancellation.** Caller cancellation flows through structured concurrency:
 * the caller's coroutine is cancelled, the lambda observes `CancellationException`,
 * the platform's OS request is cancelled (best-effort — iOS only kills *pending*
 * requests via `BGTaskScheduler.cancel(_:)`; an in-flight handler is cancelled
 * via the in-process bridge `Job`), and the deferred completes with cancellation.
 */
internal interface InstantRunner {
    /**
     * Submit [task] for instant dispatch under [taskId] and suspend until it
     * completes. The lambda runs on a platform-chosen dispatcher. Pre-emption
     * of any prior in-flight `runNow` for the same `taskId` is handled by
     * [BackgroundTaskManager.runNow] *before* this call — implementations may assume
     * the slot is clear, but should still defensively replace any leftover
     * entry to be robust against races.
     *
     * @throws IllegalStateException if the platform refuses the request (iOS:
     *   missing `Info.plist` identifier, `tooManyPendingTaskRequests`).
     * @throws kotlin.coroutines.cancellation.CancellationException if the
     *   caller's coroutine is cancelled, or if a later `runNow` for the same
     *   `taskId` pre-empts this call.
     */
    suspend fun <R> run(
        taskId: String,
        task: suspend () -> R,
    ): R

    /**
     * Cancel any in-flight `runNow` for [taskId]. Used by both
     * [BackgroundTaskManager.cancel] (the unified surface) and [BackgroundTaskManager.runNow]
     * (to enforce pre-emption).
     *
     * @return `true` if an in-flight call was cancelled; `false` if no such
     *   call existed.
     */
    fun cancelInFlight(taskId: String): Boolean
}
