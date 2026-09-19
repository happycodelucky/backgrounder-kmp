// ExperimentalForeignApi: required for cinterop FFI types (BGTask, BGAppRefreshTaskRequest).
// The cinterop surface has been stable across multiple Kotlin releases.
@file:OptIn(ExperimentalForeignApi::class)

package com.happycodelucky.backgrounder.ios

import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.CompletionGuard
import com.happycodelucky.backgrounder.PlatformCapabilities
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * The background feed of the iOS periodic-dispatch model.
 *
 * Owns a single library-defined `BGAppRefreshTaskRequest` identifier (the
 * `tickIdentifier` supplied at `BackgroundTaskManager.create(tickIdentifier:)`).
 * iOS treats this identifier as a wake-up coupon: when the app has been
 * backgrounded long enough that iOS decides to dispatch background refresh,
 * it calls our launch handler — and we use that wake-up to drain whatever
 * periodics are currently due via [IOSPeriodicDispatcher.dispatchDueWork].
 *
 * `BGAppRefreshTaskRequest` only fires while the app is **backgrounded** —
 * iOS suppresses it for foregrounded apps (which is why the
 * [IOSForegroundFeed] exists as the in-process counterpart). The two feeds
 * coalesce by task id through the dispatcher's mutex-then-advance contract:
 * if both feeds happen to fire near the same instant, only one runs each
 * cycle's worker.
 *
 * Lifecycle of one OS-driven tick:
 *  1. iOS calls [register]'s launch closure with a [BGTask].
 *  2. We construct a per-fire [CompletionGuard] (so
 *     `setTaskCompletedWithSuccess` is at-most-once across the worker-success
 *     vs expiration race — same primitive [IOSCoroutineBridge] uses for
 *     one-shots).
 *  3. We bind `task.expirationHandler` to cancel a per-fire child scope
 *     **before** launching dispatch — Apple's BGTaskScheduler can fire the
 *     expiration handler immediately after the launch closure returns; the
 *     handler must be set by then or the system kills the process (M-1 fix).
 *  4. We `dispatcher.dispatchDueWork(scope, capabilities)` and on completion
 *     call `setTaskCompletedWithSuccess(cause == null)` through the guard,
 *     then [submitNextTick] so the App Refresh request gets re-queued for
 *     the soonest upcoming `nextRunEpochMs`.
 *
 * Note that this feed does **not** dispatch one-shot tasks. One-shots
 * continue to register per-task id `BGTaskRequest`s and flow through
 * [IOSCoroutineBridge.handle]; the dispatcher pattern is periodic-only.
 */
internal class IOSBackgroundFeed(
    private val tickIdentifier: String,
    private val dispatcher: IOSPeriodicDispatcher,
) {
    private val log = Logger.withTag("Backgrounder/iOS/BackgroundFeed")

    /**
     * Per-feed scope owning all in-flight dispatches. Each [handle] invocation
     * launches its own child job; cancelling the scope at app teardown
     * propagates to every in-flight worker. Mirrors [IOSCoroutineBridge.scope].
     */
    private val scope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("Backgrounder.iOS.BackgroundFeed"),
        )

    /**
     * Capabilities reported to workers when running under the background feed.
     * `BGAppRefreshTaskRequest` budget is ~30 seconds (per Apple docs); workers
     * should checkpoint accordingly. `cancelsInFlight = false` matches the
     * existing one-shot story — `BGTaskScheduler.cancel(_:)` doesn't kill an
     * already-running worker, only pending requests.
     */
    private val capabilities = PlatformCapabilities(maxExecutionTime = 30.seconds, cancelsInFlight = false)

    /**
     * Register the launch handler for [tickIdentifier] with the shared
     * [BGTaskScheduler]. **Must be called before
     * `application(_:didFinishLaunchingWithOptions:)` returns** — Apple
     * requires the handler to be registered before launch completes or iOS
     * refuses to dispatch the identifier in this process.
     *
     * Idempotent within a process: Apple's API will log an error and return
     * `false` if called twice for the same identifier, but we don't crash on
     * that.
     */
    fun register() {
        val ok =
            BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
                identifier = tickIdentifier,
                usingQueue = null,
            ) { task: BGTask? ->
                val real =
                    task ?: run {
                        log.e { "BGTaskScheduler launch handler called with null task for $tickIdentifier" }
                        return@registerForTaskWithIdentifier
                    }
                handle(real)
            }
        if (!ok) {
            log.w { "BGTaskScheduler.register(forTaskWithIdentifier:) returned false for $tickIdentifier" }
        } else {
            log.i { "registered tick identifier '$tickIdentifier'" }
        }
    }

    /**
     * Resubmit the App Refresh request with `earliestBeginDate` set to the
     * dispatcher's soonest upcoming `nextRunEpochMs`. Idempotent — iOS
     * coalesces by identifier, so calling this multiple times in quick
     * succession just replaces the pending request.
     *
     * No-op when no active periodics exist (nothing to schedule a wake-up
     * for). Called from:
     *  - [handle] after each dispatch, to perpetuate the cycle.
     *  - [BGTaskBackedScheduler.schedulePeriodic] (step 6) when a new
     *    periodic might shift the soonest.
     *  - [BGTaskBackedScheduler.cancel] / `cancelAll` (step 6) when removing
     *    a periodic might shift the soonest the other way.
     *  - [BGTaskHandlerRegistration.resurrectActivePeriodics] (step 6) at
     *    cold-start to set up the initial pending request.
     */
    fun submitNextTick() {
        val soonest =
            dispatcher.soonestUpcomingNextRun()
                ?: run {
                    log.d { "submitNextTick: no active periodics; skipping" }
                    return
                }
        val request =
            BGAppRefreshTaskRequest(tickIdentifier).apply {
                earliestBeginDate = epochMsToNSDate(soonest)
            }
        when (val outcome = submitBGTaskRequest(request)) {
            BGSubmitResult.Success -> log.d { "submitNextTick: queued for ${soonest}ms" }
            is BGSubmitResult.Failure -> log.e { "submitNextTick failed: ${outcome.message}" }
        }
    }

    /**
     * Cancel the pending App Refresh request, if any. Called from
     * [BGTaskBackedScheduler.cancelAll] in step 6.
     */
    fun cancel() {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(tickIdentifier)
    }

    /**
     * Cancel the feed's scope. Called from the iOS builder's `onShutdown`
     * lambda — propagates [CancellationException] to every in-flight
     * dispatch; the [CompletionGuard] ensures iOS still gets exactly one
     * `setTaskCompletedWithSuccess(false)` call per active [BGTask].
     */
    fun shutdown() {
        log.i { "shutdown: cancelling background feed scope" }
        scope.cancel(CancellationException("IOSBackgroundFeed.shutdown"))
    }

    private fun handle(task: BGTask) {
        val guard = CompletionGuard()

        // ── M-1: register the expiration handler BEFORE launching dispatch.
        // Apple's BGTaskScheduler can fire expiration any time after this
        // launch closure returns; if `setExpirationHandler` is unset at that
        // moment, the system kills the process. Capture the job via a lateinit
        // slot — the handler reads it only when the system invokes the
        // handler, which is necessarily after `scope.launch` has assigned the
        // field.
        var job: Job? = null
        task.setExpirationHandler {
            log.w { "tick BGTask expired; cancelling in-flight dispatch" }
            job?.cancel(CancellationException("BGTask expired"))
        }

        job =
            scope.launch {
                try {
                    dispatcher.dispatchDueWork(scope = this, capabilities = capabilities)
                } catch (e: CancellationException) {
                    log.i { "dispatch cancelled (likely BGTask expiration): ${e.message}" }
                    throw e
                } catch (t: Throwable) {
                    log.e(t) { "dispatchDueWork threw" }
                    // Don't re-throw — let invokeOnCompletion treat this as a
                    // failed task completion (cause != null).
                }
                // Successful path: complete the BGTask with success, queue the
                // next tick, and we're done. The guard ensures the
                // invokeOnCompletion fallback below won't double-complete on
                // races.
                guard.runOnce { task.setTaskCompletedWithSuccess(true) }
                submitNextTick()
            }

        // C-1 mirror: cancellation/expiration fallback also goes through the
        // guard. If a successful dispatch above already called
        // setTaskCompletedWithSuccess(true), this is a no-op. If the OS
        // expired us mid-dispatch, this is the only completion call.
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                guard.runOnce { task.setTaskCompletedWithSuccess(false) }
                // Even on expiration, queue the next tick — the dispatcher
                // already advanced nextRunEpochMs for any worker that started,
                // so we know the next due time. Unstarted workers retain their
                // old nextRunEpochMs (still in the past), so they'll fire on
                // the next tick.
                submitNextTick()
            }
        }
    }
}
