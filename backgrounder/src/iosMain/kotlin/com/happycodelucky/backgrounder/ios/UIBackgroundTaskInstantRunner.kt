// ExperimentalForeignApi: required for cinterop FFI types (UIBackgroundTaskIdentifier).
// Stable in practice — UIKit interop has been part of Kotlin/Native since 1.3.
@file:OptIn(ExperimentalForeignApi::class)

package com.happycodelucky.backgrounder.ios

import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.InstantRunner
import com.happycodelucky.backgrounder.PendingInstantCalls
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import kotlin.coroutines.cancellation.CancellationException

/**
 * iOS [InstantRunner] backed by `UIApplication.beginBackgroundTask(withName:expirationHandler:)`.
 *
 * This is **not** [platform.BackgroundTasks.BGTaskScheduler]. `BGTaskScheduler`
 * is for *deferred* "wake me later" work and requires:
 *  - a permitted-identifiers entry in `Info.plist`,
 *  - a launch-handler `register(forTaskWithIdentifier:)` call inside
 *    `application(_:didFinishLaunchingWithOptions:)`.
 *
 * `runNow` is the opposite — *immediate* work the caller is `await`-ing right
 * now. iOS supports this via `UIApplication.beginBackgroundTask`, which:
 *  - has **no `Info.plist` requirement**,
 *  - has **no launch-time registration requirement** (the task id is just an
 *    in-process pre-emption key — never sent to iOS),
 *  - grants up to ~30 seconds of grace runtime if the user backgrounds the
 *    app while the lambda is in flight.
 *
 * Trade-offs (CLAUDE.md §6 — when not to roll our own; this isn't rolling our
 * own, just choosing the right Apple primitive):
 *  - **Cap**: ~30 seconds. Workers needing more must use the scheduled path.
 *  - **No app-not-running survival**: a force-quit kills the lambda. (Same as
 *    every coroutine-rooted execution.)
 *  - **`UIApplication.sharedApplication`** is read once and cached — accessing
 *    it requires the main thread on some Apple SDKs. We do this from the
 *    runner's `CoroutineScope` rooted at `Dispatchers.Default` and access
 *    `sharedApplication` lazily on each `run` call to keep the contract simple
 *    (Apple has loosened the main-thread requirement for `sharedApplication`
 *    itself in recent SDKs; it's `begin/endBackgroundTask` that the docs are
 *    less prescriptive about).
 *
 * The `expirationHandler` cancels the in-flight job. The `try/finally` in
 * `run` always calls `endBackgroundTask` — exactly once per `beginBackgroundTask`
 * (Apple raises `NSInternalInconsistencyException` for unbalanced calls).
 */
internal class UIBackgroundTaskInstantRunner(
    private val pending: PendingInstantCalls,
) : InstantRunner {
    private val log = Logger.withTag("Backgrounder/iOS/InstantRunner")

    // CLAUDE.md §3 — owned scope with documented cancellation lifecycle.
    private val scope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("Backgrounder.iOS.runNow"),
        )

    // Used to serialize `endBackgroundTask` calls per-entry. UIBackgroundTask's
    // begin/end pair must balance exactly once; without a guard the
    // expiration handler + the try/finally cleanup can both call `end`.
    // MUST NOT call suspend functions inside synchronized blocks.
    private val lock = SynchronizedObject()

    override suspend fun <R> run(
        taskId: String,
        task: suspend () -> R,
    ): R {
        val deferred = CompletableDeferred<Any?>()
        val erasedTask: suspend () -> Any? = { task() }
        val entry = PendingInstantCalls.Entry(taskId, erasedTask, deferred)

        // Defensive replace — see WorkManagerInstantRunner / LibraryScopeInstantRunner.
        pending.put(entry)?.let { prior ->
            prior.job?.cancel(CancellationException("pre-empted by newer runNow($taskId)"))
            prior.deferred.cancel(CancellationException("pre-empted by newer runNow($taskId)"))
        }

        // Begin the iOS background task. The expiration handler cancels the
        // job; `endBackgroundTask` is balanced by the `try/finally` below.
        val ended = EndOnceFlag()
        val bgTaskName = "BackgroundTaskManager.runNow($taskId)"
        var bgTaskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
        bgTaskId =
            UIApplication.sharedApplication.beginBackgroundTaskWithName(bgTaskName) {
                // iOS expiration handler — runs on the main queue. Must not block.
                log.w { "$bgTaskName: expired; cancelling" }
                entry.job?.cancel(CancellationException("UIApplication background task expired"))
                // The cleanup `endBackgroundTask` will fire from the try/finally
                // path below once the job's CancellationException unwinds.
                // We do NOT call endBackgroundTask here ourselves — Apple guarantees
                // that the handler returning *is* the signal; calling end inside
                // the handler is also valid but we centralize it for symmetry.
            }
        if (bgTaskId == UIBackgroundTaskInvalid) {
            // iOS refused to grant background time (e.g., low-power mode while
            // already backgrounded). We still run the lambda — it just won't
            // get protection from suspension.
            log.i { "$bgTaskName: iOS returned UIBackgroundTaskInvalid; running without background grace" }
        }
        entry.platformHandle = bgTaskId

        entry.job =
            scope.launch {
                try {
                    val result = task()
                    deferred.complete(result)
                } catch (e: CancellationException) {
                    deferred.cancel(e)
                    throw e
                } catch (t: Throwable) {
                    log.d(t) { "runNow($taskId) lambda threw" }
                    deferred.completeExceptionally(t)
                }
            }

        return try {
            @Suppress("UNCHECKED_CAST")
            deferred.await() as R
        } finally {
            // Drop the slot only if we still own it.
            pending.removeIfSame(taskId, entry)
            entry.job?.cancel()
            // Balance the begin call — exactly once.
            ended.endIfFirst(bgTaskId)
        }
    }

    override fun cancelInFlight(taskId: String): Boolean {
        val entry = pending.take(taskId) ?: return false
        entry.job?.cancel(CancellationException("Backgrounder.cancel($taskId)"))
        entry.deferred.cancel(CancellationException("Backgrounder.cancel($taskId)"))
        // The owning `run` call will end the UIBackgroundTask in its `finally`.
        // We do NOT call endBackgroundTask here — that would double-end.
        return true
    }

    /**
     * Cancel the runner-owned scope. Called from `BackgroundTaskManager.shutdown` via the iOS builder.
     */
    fun shutdown() {
        log.i { "shutdown: cancelling BackgroundTaskManager.iOS.runNow scope" }
        scope.cancel(CancellationException("UIBackgroundTaskInstantRunner.shutdown"))
    }

    /**
     * Single-shot guard around `endBackgroundTask`. Apple's `begin`/`end` calls
     * must balance exactly — calling `end` twice raises
     * `NSInternalInconsistencyException`. The expiration handler and the
     * try/finally cleanup can both reach this code path; the flag ensures
     * only the first wins.
     */
    private inner class EndOnceFlag {
        private var done = false

        fun endIfFirst(bgTaskId: UIBackgroundTaskIdentifier) {
            val shouldEnd =
                synchronized(lock) {
                    if (done || bgTaskId == UIBackgroundTaskInvalid) {
                        false
                    } else {
                        done = true
                        true
                    }
                }
            if (shouldEnd) {
                UIApplication.sharedApplication.endBackgroundTask(bgTaskId)
            }
        }
    }
}
