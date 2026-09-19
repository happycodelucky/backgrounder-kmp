package com.happycodelucky.backgrounder

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Desktop-class [InstantRunner] — runs the lambda on a library-owned `SupervisorJob`
 * scope, with no platform scheduler in the path.
 *
 * Used by the platforms where no OS primitive mediates one-shot foreground work:
 *  - **macOS** — `NSBackgroundActivityScheduler` is interval-shaped and a poor
 *    fit for one-shot work; the app is generally foregrounded, so we don't need
 *    OS-granted background time for `runNow`.
 *  - **JVM** (desktop / server) — there is no OS background scheduler at all;
 *    the process is fully ours.
 *
 * iOS (`UIBackgroundTaskInstantRunner`) and Android (`WorkManagerInstantRunner`)
 * route through their OS primitives instead, to earn background runtime if the
 * app is suspended mid-call.
 *
 * Owns its own `CoroutineScope("Backgrounder.<platformLabel>.runNow")` distinct
 * from the platform scheduler's scheduling scope (CLAUDE.md §3 — one clear owner
 * per scope). [shutdown] cancels it; each platform builder threads shutdown
 * through `Backgrounder.shutdown` alongside the scheduler.
 *
 * @param platformLabel short platform discriminator (`"macOS"`, `"JVM"`) baked
 *   into the Kermit tag and the scope's [CoroutineName] so logs and debugger
 *   coroutine dumps read the same as before the runner was shared.
 */
internal class LibraryScopeInstantRunner(
    private val pending: PendingInstantCalls,
    platformLabel: String,
) : InstantRunner {
    private val log = Logger.withTag("Backgrounder/$platformLabel/InstantRunner")

    private val scope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("Backgrounder.$platformLabel.runNow"),
        )

    override suspend fun <R> run(
        taskId: String,
        task: suspend () -> R,
    ): R {
        // Type-erased deferred — `R` is preserved through the closure & the cast on resume.
        @Suppress("UNCHECKED_CAST")
        val deferred = CompletableDeferred<Any?>()
        val erasedTask: suspend () -> Any? = { task() }
        val entry = PendingInstantCalls.Entry(taskId, erasedTask, deferred)

        // Pre-emption: Backgrounder.runNow already called cancel(taskId) before
        // us, but defensively replace any leftover entry to be robust against
        // races. If a prior entry IS still here, cancel its deferred outside
        // the lock (CLAUDE.md §3).
        pending.put(entry)?.let { prior ->
            prior.job?.cancel(CancellationException("pre-empted by newer runNow($taskId)"))
            prior.deferred.cancel(CancellationException("pre-empted by newer runNow($taskId)"))
        }

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
            // Clean up only if we still own the slot — pre-emption may have
            // replaced us, in which case the new entry must remain.
            pending.removeIfSame(taskId, entry)
            entry.job?.cancel()
        }
    }

    override fun cancelInFlight(taskId: String): Boolean {
        val entry = pending.take(taskId) ?: return false
        entry.job?.cancel(CancellationException("Backgrounder.cancel($taskId)"))
        entry.deferred.cancel(CancellationException("Backgrounder.cancel($taskId)"))
        return true
    }

    /** Cancel the runner-owned scope. Called from `Backgrounder.shutdown` via the platform builder. */
    fun shutdown() {
        log.i { "shutdown: cancelling the runNow scope" }
        scope.cancel(CancellationException("LibraryScopeInstantRunner.shutdown"))
    }
}
