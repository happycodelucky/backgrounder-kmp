package com.happycodelucky.backgrounder

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.SharedFlow

/**
 * The internal engine held by [BackgroundTaskManager] — it owns the per-platform graph
 * (registry + scheduler + instant runner), the started-state flag, the
 * [MonitorEventEmitter] shared between every emit-site in the library, and
 * the platform-specific `start` / `shutdown` lambdas the public
 * `BackgroundTaskManager.start()` and `.shutdown()` calls dispatch to.
 *
 * Constructed by per-platform builders (`AndroidBackgrounderBuilder`,
 * `IOSBackgrounderBuilder`, `MacOSBackgrounderBuilder`) inside `androidMain` /
 * `iosMain` / `macosMain`. Plan §"DI-free initialization" §1.1.
 *
 * The forward-reference puzzle that DI used to solve (a closure capturing an
 * object that hasn't been built yet) is dissolved here by *constructor order* —
 * the platform builder constructs the scheduler first, then captures it inside
 * the `onStart` / `onShutdown` lambdas it passes here. No `lateinit`, no `Lazy`.
 *
 * Wave-1 footprint: the engine owns the emitter but does not yet drive it.
 * Platform schedulers continue to call their `BackgrounderEventListener`
 * directly. The emit-site swap to `emitter.emit(…)` lands in wave 2 — at
 * that point each platform builder will receive a `MonitorEventEmitter`
 * instead of (or alongside) the raw listener.
 */
internal class BackgrounderEngine(
    val registry: WorkerRegistry,
    val scheduler: Scheduler,
    val instantRunner: InstantRunner,
    val emitter: MonitorEventEmitter,
    private val onStart: () -> Unit,
    private val onShutdown: () -> Unit,
) {
    /** Read-only event stream — surfaced as `BackgroundTaskManager.events()`. */
    val events: SharedFlow<MonitorEvent> get() = emitter.events

    private val started = atomic(false)

    /**
     * Whether [start] has been called. Read by [BackgroundTaskManager.runNow] to gate
     * dispatch — instant runs require the registry to be sealed before they
     * can rely on stable platform handlers being installed.
     */
    val isStarted: Boolean get() = started.value

    /** Idempotent — repeated calls return without invoking [onStart] again. */
    fun start() {
        if (!started.compareAndSet(expect = false, update = true)) return
        registry.seal()
        onStart()
    }

    /** Idempotent — repeated calls invoke [onShutdown] each time, but the
     *  per-platform shutdown logic is itself idempotent (cancels a scope, etc).
     *  We don't gate this with the started flag because shutdown should always
     *  succeed even if start() was never called. */
    fun shutdown() {
        onShutdown()
    }
}
