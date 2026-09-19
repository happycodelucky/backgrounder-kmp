// ExperimentalForeignApi: required for cinterop FFI types (NSNotificationCenter,
// UIApplication, NSNotification, UIBackgroundTaskIdentifier, etc.). Stable in
// practice across multiple Kotlin releases.
//
// CLAUDE.md §1 says the shared module is "headless... not UI." This file is
// the deliberate exception: it observes `UIApplication` *lifecycle*
// (foreground/background transitions) and reserves a slice of OS-granted
// background runway via `beginBackgroundTaskWithName:`. Neither is UI
// rendering; both are app-lifecycle concerns that the dispatcher needs to
// react to. The rule's intent — keep UI framework dependencies (UIKit views,
// SwiftUI, AppKit views) out of headless logic — isn't violated. If a future
// reader sees `import platform.UIKit.*` in iosMain and wonders, this comment
// is the why.
@file:OptIn(ExperimentalForeignApi::class)

package com.happycodelucky.backgrounder.ios

import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.PlatformCapabilities
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.NSObjectProtocol
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * The foreground feed of the iOS periodic-dispatch model.
 *
 * Owns a single in-process loop coroutine that, while the app is
 * foregrounded, sleeps until [IOSPeriodicDispatcher.soonestUpcomingNextRun]
 * and then drains the dispatcher. iOS's `BGAppRefreshTaskRequest` (the
 * background feed's path) does **not** fire while the app is foregrounded,
 * so without this in-process feed a periodic whose interval elapses during
 * a long user session would silently slip past its cycle until the user
 * backgrounds the app.
 *
 * The two feeds coalesce by task id through the dispatcher's mutex-then-
 * advance contract: if the foreground loop and a background tick race for
 * the same due task, only one cycle's worker runs.
 *
 * Lifecycle:
 *  - [start] subscribes to [UIApplicationWillEnterForegroundNotification] and
 *    [UIApplicationDidEnterBackgroundNotification]. If the app is foregrounded
 *    at start time, the loop is launched immediately.
 *  - On `WillEnterForeground`, the loop coroutine is launched on this feed's
 *    [scope].
 *  - On `DidEnterBackground`, the loop coroutine is cancelled (the scope
 *    survives — restarted on the next foreground entry). State remains
 *    durable in [IOSStateStore]; the [IOSBackgroundFeed] takes over via
 *    iOS-driven `BGAppRefreshTaskRequest` dispatch.
 *  - [kick] wakes the loop early so it can re-evaluate its `delay()` after
 *    [BGTaskBackedScheduler.schedule] / `cancel` has changed the soonest
 *    upcoming `nextRunEpochMs`.
 *  - [shutdown] removes both observers and cancels the scope.
 *
 * Each dispatch is wrapped in a `UIApplication.beginBackgroundTaskWithName`
 * runway so foreground-initiated work that gets backgrounded mid-execution
 * gets the OS-granted continuation window (~30 seconds, sometimes a few
 * minutes). If the OS expires the background task before the worker
 * finishes, cancellation propagates from the runway's expiration handler
 * into the dispatch scope. This is the only continuation safety net the
 * library offers — there is no `BGProcessingTaskRequest` fallback.
 */
internal class IOSForegroundFeed(
    private val dispatcher: IOSPeriodicDispatcher,
    // Injectable so tests can drive virtual time. Production reads wall-clock.
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val log = Logger.withTag("Backgrounder/iOS/ForegroundFeed")

    /**
     * Per-feed scope. Notification observers schedule loop launches onto it;
     * each foreground entry creates a child loop job. Cancelled in [shutdown].
     */
    private val scope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("Backgrounder.iOS.ForegroundFeed"),
        )

    /**
     * Conflated channel — multiple `kick()` calls in quick succession
     * coalesce to a single wake-up. The loop selects on a `delay()` and a
     * `kickChannel.onReceive`; whichever wins, the loop recomputes the
     * soonest and either dispatches or sleeps again.
     */
    private val kickChannel = Channel<Unit>(capacity = Channel.CONFLATED)

    /**
     * Foreground capabilities — no OS budget on the in-process path. We
     * report a generous hint; workers should still budget for the *background*
     * path, since their work may be interrupted by a backgrounding event.
     */
    private val capabilities = PlatformCapabilities(maxExecutionTime = Duration.INFINITE, cancelsInFlight = true)

    // Observer handles from NSNotificationCenter.addObserverForName(...).
    // Held for the lifetime of the feed so removeObserver can untie them on
    // shutdown. Marked atomic so start()/shutdown() can sequence cleanly even
    // if invoked from different threads (defensive — both should run on the
    // main thread today).
    private val foregroundObserver = atomic<NSObjectProtocol?>(null)
    private val backgroundObserver = atomic<NSObjectProtocol?>(null)

    // The currently-running loop job. null when backgrounded. Replaced
    // atomically when the foreground observer launches a new loop.
    private val loopJob = atomic<Job?>(null)

    private val started = atomic(false)

    /**
     * Subscribe to UIApplication lifecycle notifications and, if the app is
     * already foregrounded, launch the loop immediately.
     *
     * Idempotent: a second `start()` after a successful first one is a no-op.
     */
    fun start() {
        if (!started.compareAndSet(expect = false, update = true)) {
            log.d { "start: already started; ignoring" }
            return
        }
        val center = NSNotificationCenter.defaultCenter

        foregroundObserver.value =
            center.addObserverForName(
                name = UIApplicationWillEnterForegroundNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                log.d { "foreground notification: launching loop" }
                launchLoop()
            }

        backgroundObserver.value =
            center.addObserverForName(
                name = UIApplicationDidEnterBackgroundNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                log.d { "background notification: cancelling loop" }
                loopJob.getAndSet(null)?.cancel(CancellationException("app backgrounded"))
            }

        // If we're already foregrounded at start time (the typical case when
        // start() is called from application:didFinishLaunchingWithOptions:),
        // launch the loop right away instead of waiting for the next
        // foreground transition.
        if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) {
            log.d { "start: app already active; launching loop" }
            launchLoop()
        }
    }

    /**
     * Wake the loop early. Called from the scheduler whenever a `schedule()`
     * or `cancel()` may have changed the dispatcher's soonest upcoming
     * `nextRunEpochMs`. No-op if the loop isn't currently running (e.g.
     * the app is backgrounded — the background feed picks up via
     * [IOSBackgroundFeed.submitNextTick] instead).
     */
    fun kick() {
        // Conflated channel: trySend either delivers or replaces a pending
        // sentinel. Either way the loop's `select` will observe it.
        kickChannel.trySend(Unit)
    }

    /**
     * Tear down: remove both observers, cancel the scope. Safe to call from
     * any thread; safe to call before [start] (no-op).
     */
    fun shutdown() {
        if (!started.compareAndSet(expect = true, update = false)) {
            log.d { "shutdown: not started; ignoring" }
            return
        }
        val center = NSNotificationCenter.defaultCenter
        foregroundObserver.getAndSet(null)?.let { center.removeObserver(it) }
        backgroundObserver.getAndSet(null)?.let { center.removeObserver(it) }
        log.i { "shutdown: cancelling foreground feed scope" }
        scope.cancel(CancellationException("IOSForegroundFeed.shutdown"))
    }

    private fun launchLoop() {
        // Replace any existing loop atomically: getAndSet wins the slot first,
        // then the displaced loop (if alive) is cancelled. The read-check-write
        // version of this raced with itself when start() was called off the
        // main thread, leaking an uncancellable loop. LAZY start keeps the new
        // loop from dispatching while the old one is still being retired.
        val job = scope.launch(start = CoroutineStart.LAZY) { runLoop() }
        val previous = loopJob.getAndSet(job)
        if (previous?.isActive == true) {
            log.w { "launchLoop: previous loop still active; cancelling" }
            previous.cancel(CancellationException("loop replaced"))
        }
        job.start()
    }

    private suspend fun runLoop() {
        log.d { "loop: started" }
        try {
            while (true) {
                val soonest = dispatcher.soonestUpcomingNextRun()
                val now = clock()
                val delayMs = soonest?.let { (it - now).coerceAtLeast(0L) }
                // If no active periodics, sleep indefinitely until either
                // a kick (someone scheduled one) or cancellation.
                val dispatched =
                    if (delayMs == null) {
                        log.d { "loop: no active periodics; awaiting kick" }
                        select {
                            kickChannel.onReceive { false }
                        }
                    } else if (delayMs == 0L) {
                        // Already due — dispatch without sleeping.
                        true
                    } else {
                        log.d { "loop: sleeping ${delayMs}ms until next due (or kick)" }
                        // Race a kick against the deadline. withTimeoutOrNull returns null on
                        // timeout (-> dispatch), or false if the channel delivers first (-> recompute).
                        withTimeoutOrNull(delayMs.toDuration(DurationUnit.MILLISECONDS)) {
                            kickChannel.receive()
                            false
                        } ?: true
                    }
                if (dispatched) {
                    dispatchUnderRunway()
                }
                // Loop iteration: kick wakes us early to recompute soonest;
                // timeout wakes us at the right moment to dispatch.
            }
        } catch (e: CancellationException) {
            log.d { "loop: cancelled (${e.message})" }
            throw e
        } finally {
            log.d { "loop: exited" }
        }
    }

    /**
     * Run one dispatch invocation under a `UIApplication.beginBackgroundTaskWithName`
     * runway. iOS gives us several extra seconds (sometimes a few minutes)
     * past a backgrounding event before suspending the process. If the OS
     * reclaims the runway before we finish, the expiration handler cancels
     * a per-dispatch child scope — propagating cancellation to in-flight
     * workers without affecting the loop itself (the next iteration just
     * re-computes and either dispatches again or sleeps).
     *
     * `taskId` is held in atomicfu so the suspending block and the
     * OS-queue-invoked expiration handler can both read/write it without
     * a data race.
     */
    private suspend fun dispatchUnderRunway() {
        val app = UIApplication.sharedApplication
        val taskId = atomic(UIBackgroundTaskInvalid)
        // The dispatch scope is a child of the feed's scope: completion
        // happens via `coroutineScope { ... }` (joins on all children),
        // and cancellation can be targeted through the dispatch's Job
        // captured below.
        val dispatchJob: CompletableJob = SupervisorJob(scope.coroutineContext[Job])

        // Begin the runway *before* launching the work, so a near-immediate
        // backgrounding still hands us the runway window. Apple's contract:
        // expirationHandler may be called on a background queue at any time.
        taskId.value =
            app.beginBackgroundTaskWithName(
                taskName = "Backgrounder.iOS.ForegroundFeed.dispatch",
                expirationHandler = {
                    log.w { "background task runway expired; cancelling in-flight dispatch" }
                    dispatchJob.cancel(CancellationException("background task runway expired"))
                    // End the task immediately — Apple requires this before
                    // the handler returns or the OS will terminate the app.
                    val id = taskId.getAndSet(UIBackgroundTaskInvalid)
                    if (id != UIBackgroundTaskInvalid) {
                        app.endBackgroundTask(id)
                    }
                },
            )

        try {
            // coroutineScope joins on all children launched inside it. The
            // dispatcher's `dispatchDueWork` uses `scope.launch` and then
            // `joinAll` — so the suspend returns when every worker has
            // observed completion or cancellation.
            //
            // We launch the dispatch on a child scope that's parented under
            // dispatchJob, so the runway's expirationHandler can cancel
            // only this dispatch (not the loop or the feed).
            coroutineScope {
                val child = CoroutineScope(coroutineContext + dispatchJob)
                dispatcher.dispatchDueWork(scope = child, capabilities = capabilities)
            }
        } catch (e: CancellationException) {
            log.i { "dispatch cancelled: ${e.message}" }
            // Don't re-throw on runway expiration — it's a normal lifecycle
            // event, not an error. Re-throw if the loop itself was cancelled
            // (different cause path).
            if (coroutineContext[Job]?.isActive == false) throw e
        } finally {
            val id = taskId.getAndSet(UIBackgroundTaskInvalid)
            if (id != UIBackgroundTaskInvalid) {
                app.endBackgroundTask(id)
            }
            // Mark the dispatchJob complete so any orphaned children clean up.
            dispatchJob.complete()
        }
    }
}
