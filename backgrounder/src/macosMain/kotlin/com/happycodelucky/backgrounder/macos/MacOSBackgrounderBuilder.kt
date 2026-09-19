// ExperimentalForeignApi: required by `NSUserDefaults(suiteName:)` cinterop call.
@file:OptIn(ExperimentalForeignApi::class)

package com.happycodelucky.backgrounder.macos

import com.happycodelucky.backgrounder.Backgrounder
import com.happycodelucky.backgrounder.BackgrounderEngine
import com.happycodelucky.backgrounder.BackgrounderEventListener
import com.happycodelucky.backgrounder.EphemeralRegistry
import com.happycodelucky.backgrounder.LibraryScopeInstantRunner
import com.happycodelucky.backgrounder.MonitorEventEmitter
import com.happycodelucky.backgrounder.PendingInstantCalls
import com.happycodelucky.backgrounder.ReachabilityGate
import com.happycodelucky.backgrounder.SharedBackgrounder
import com.happycodelucky.backgrounder.WorkerRegistry
import com.happycodelucky.reachable.Reachability
import com.russhwolf.settings.NSUserDefaultsSettings
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUserDefaults

/**
 * Constructor-injection wiring for the macOS [Backgrounder] graph.
 *
 * Replaces the Koin module wiring in `backgrounderMacOSModule` (plan §"DI-free
 * initialization" §2.2). The macOS graph is shorter than iOS's because
 * `NSBackgroundActivityScheduler` is in-process and doesn't need cold-launch
 * handler registration:
 *
 *  - no state store (the scheduler holds its own activity map),
 *  - no coroutine-bridge object (the scheduler launches its own coroutines),
 *  - no plist-validation step (no Info.plist registry exists on macOS).
 *
 * `start()` only needs to clear ephemeral entries from our mirror;
 * `shutdown()` cancels the scheduler's [kotlinx.coroutines.SupervisorJob]-rooted scope.
 */
internal object MacOSBackgrounderBuilder {
    fun build(eventListener: BackgrounderEventListener): Backgrounder {
        val settings = NSUserDefaultsSettings(NSUserDefaults(suiteName = "com.happycodelucky.backgrounder.shared"))
        val ephemeral = EphemeralRegistry(settings)
        val registry = WorkerRegistry()

        // Pre-execution network gate. Driven by `Reachability.shared` —
        // process-lifetime singleton. Tests override the singleton via
        // the `:reachable-testing` artifact's `withFakeReachability { }`
        // install hook; no Backgrounder-side parameter is needed.
        //
        // Warm up the platform observer now by reading isReachable once —
        // Reachability.shared lazily constructs its nw_path_monitor on
        // first access (cold-read cost ~10–100ms on Apple); forcing it
        // here keeps the first scheduled worker out of the cold path.
        val gate = ReachabilityGate(Reachability.shared)
        Reachability.shared.isReachable // discarded — read is the warmup side-effect

        // Shared emitter — feeds both the v1 listener (for the four v1-shape
        // events) and the SharedFlow exposed via Backgrounder.events().
        val emitter = MonitorEventEmitter(eventListener)

        val scheduler =
            NSBackgroundActivityBackedScheduler(
                registry = registry,
                ephemeral = ephemeral,
                emitter = emitter,
                gate = gate,
            )

        // The instant runner is owned by Backgrounder, not Scheduler — see plan
        // §"Why a separate runner type rather than reusing Scheduler". macOS's
        // runner runs the lambda directly on a library scope; no platform
        // scheduler is involved. (Shared with JVM — the runner lives in
        // commonMain; the label keeps macOS log tags unchanged.)
        val pendingInstantCalls = PendingInstantCalls()
        val instantRunner = LibraryScopeInstantRunner(pendingInstantCalls, platformLabel = "macOS")

        // Snapshot at construction so a pre-start ephemeral schedule survives the sweep.
        val leftoverEphemeral = ephemeral.snapshot()
        val backgrounder =
            Backgrounder(
                BackgrounderEngine(
                    registry = registry,
                    scheduler = scheduler,
                    instantRunner = instantRunner,
                    emitter = emitter,
                    onStart = {
                        // macOS has no OS-level "registered task ids" concept; the
                        // ephemeral sweep just clears our mirror. (Plan §2.2.)
                        leftoverEphemeral.forEach(ephemeral::remove)
                    },
                    onShutdown = {
                        scheduler.shutdown()
                        instantRunner.shutdown()
                    },
                ),
            )
        SharedBackgrounder.install(backgrounder)
        return backgrounder
    }
}
