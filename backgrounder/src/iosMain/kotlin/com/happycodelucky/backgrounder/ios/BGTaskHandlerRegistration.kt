// ExperimentalForeignApi: required for cinterop FFI types. Stable in practice.
@file:OptIn(ExperimentalForeignApi::class)

package com.happycodelucky.backgrounder.ios

import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.WorkerRegistry
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSBundle
import kotlin.time.Clock

/**
 * Backs `BackgroundTaskManager.start()` on iOS. Run from the iOS builder's `onStart`
 * lambda, which itself runs inside `application(_:didFinishLaunchingWithOptions:)`
 * after every worker factory is registered.
 *
 * Does, in order:
 *  1. Validates the tick identifier appears in `BGTaskSchedulerPermittedIdentifiers`
 *     (mandatory — Kermit error if missing). Soft-validates each registered
 *     factory id (warning — may not need its own entry if used only for
 *     periodics post-cut-over).
 *  2. Registers the tick identifier's launch handler via [IOSBackgroundFeed]
 *     for periodic dispatch.
 *  3. Calls `BGTaskScheduler.register(forTaskWithIdentifier:using:launchHandler:)`
 *     for each registered factory id, dispatching to [IOSCoroutineBridge.handle].
 *     This is conservative coverage: at registration time we don't know which
 *     factories will be used as one-shot vs periodic. Periodics post-cut-over
 *     never fire through this path (nothing submits per-id requests for them
 *     in [BGTaskBackedScheduler.schedulePeriodic] anymore — step 6); one-shots
 *     continue to use it.
 *  4. Resurrects active periodic schedules via [IOSBackgroundFeed.submitNextTick] —
 *     a single tick request is queued for the soonest upcoming `nextRunEpochMs`
 *     (force-quit + relaunch recovery; replaces the old per-id resubmission
 *     loop in step 6).
 *  5. Seals the [WorkerRegistry] so further [WorkerRegistry.register] calls
 *     throw — at this point launch has begun in earnest.
 */
internal class BGTaskHandlerRegistration(
    private val registry: WorkerRegistry,
    private val state: IOSStateStore,
    private val bridge: IOSCoroutineBridge,
    // The library-owned `BGAppRefreshTaskRequest` identifier supplied by the
    // user at `BackgroundTaskManager.create(tickIdentifier:)`. Used both for plist
    // validation and to register the background feed's launch handler.
    private val tickIdentifier: String,
    private val backgroundFeed: IOSBackgroundFeed,
) {
    private val log = Logger.withTag("Backgrounder/iOS/Registration")

    fun run() {
        val ids = registry.registeredIds()
        if (ids.isEmpty()) {
            log.w { "registerHandlers() called with no factories registered; nothing to do" }
            // Even with no factories, the tick still needs registration if any
            // active periodic exists in persisted state — but with no factories
            // we can't dispatch anyway, so skipping is correct.
            return
        }
        validatePlistIdentifiers(ids)
        // Step 4: register the tick identifier alongside per-id handlers.
        // Both paths coexist transitionally — the per-id handlers still serve
        // periodics that schedulePeriodic submits (one BGProcessingTaskRequest
        // per task id, today's behaviour), and the tick handler stands ready
        // for the cut-over in step 6 when schedulePeriodic stops doing that.
        // One-shots will continue to use per-id handlers permanently.
        backgroundFeed.register()
        ids.forEach(::registerOne)
        resurrectActivePeriodics()
        registry.seal()
    }

    private fun validatePlistIdentifiers(ids: Set<String>) {
        val permitted =
            NSBundle.mainBundle
                .objectForInfoDictionaryKey(PLIST_KEY)
                ?.let {
                    // K/N erases element types — `as? List<String>` succeeds on any
                    // List<*>. filterIsInstance drops non-String entries safely.
                    (it as? List<*>)?.filterIsInstance<String>()
                }?.toSet()
                .orEmpty()
        if (permitted.isEmpty()) {
            log.e {
                "Info.plist key '$PLIST_KEY' is missing or empty. " +
                    "Add the Backgrounder tick identifier '$tickIdentifier' (and per-task id " +
                    "entries for any one-shot tasks) under this key, or BGTaskScheduler will " +
                    "refuse to dispatch them."
            }
            return
        }
        // Mandatory: the tick identifier MUST be present. Without it,
        // background dispatch of periodics is dead in the water.
        if (tickIdentifier !in permitted) {
            log.e {
                "tick identifier '$tickIdentifier' is not in '$PLIST_KEY'; iOS will refuse to " +
                    "dispatch background refresh for any periodic task. Add it to Info.plist."
            }
        }
        // Soft validation for registered factory ids: at registration time we
        // don't know which factories will be used as `WorkRequest.OneTime` vs
        // `WorkRequest.Periodic` — that's decided per `schedule()` call.
        // Periodic-only ids never need a per-id plist entry post-cut-over;
        // one-shot ids do. Warn so users with one-shots get the hint, but
        // don't fail-loud — ids used only as periodics shouldn't be required.
        // Future enhancement: the `schedule()` path could error loud at the
        // *use site* if a OneTime is scheduled for an id whose plist entry is
        // missing — would catch the misconfiguration earlier than iOS's
        // silent dispatch refusal at runtime. For now, the warning above plus
        // iOS's own logging are the diagnostic path.
        ids
            .filter { it !in permitted }
            .forEach { missing ->
                log.w {
                    "task id '$missing' is not in '$PLIST_KEY'. If you schedule it as " +
                        "WorkRequest.OneTime, iOS will refuse to dispatch it; periodic-only ids " +
                        "do not need their own entry."
                }
            }
    }

    private fun registerOne(taskId: String) {
        val ok =
            BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
                identifier = taskId,
                usingQueue = null,
            ) { task: BGTask? ->
                val real =
                    task ?: run {
                        log.e { "BGTaskScheduler launch handler called with null task for $taskId" }
                        return@registerForTaskWithIdentifier
                    }
                bridge.handle(real, taskId)
            }
        if (!ok) {
            log.w { "BGTaskScheduler.register(forTaskWithIdentifier:) returned false for $taskId" }
        }
    }

    private fun resurrectActivePeriodics() {
        // Step 6 cut-over: periodics no longer have per-task id BGTaskRequests.
        // Resurrection collapses to two operations:
        //  1. Re-anchor each active periodic's `nextRunEpochMs` so it's at least
        //     one full interval past `lastRunEpochMs` and never in the past
        //     (the "don't catch up missed cycles" coalescing rule lives here).
        //  2. Have the background feed queue a single tick request for the
        //     soonest of those next-runs. iOS coalesces by identifier, so this
        //     is idempotent across multiple cold starts.
        val nowMs = Clock.System.now().toEpochMilliseconds()
        var resurrected = 0
        state
            .knownTaskIds()
            .filter { state.readKind(it) == IOSStateStore.Kind.Periodic && state.readActive(it) }
            .forEach { id ->
                val intervalMs = state.readIntervalMs(id) ?: return@forEach
                val lastRunMs = state.readLastRunEpochMs(id) ?: nowMs
                val nextRunMs = maxOf(nowMs, lastRunMs + intervalMs)
                state.setNextRunEpochMs(id, nextRunMs)
                resurrected++
                log.i { "resurrected periodic $id; next run at ${nextRunMs}ms" }
            }
        if (resurrected > 0) {
            backgroundFeed.submitNextTick()
        }
    }

    internal companion object {
        internal const val PLIST_KEY: String = "BGTaskSchedulerPermittedIdentifiers"
    }
}
