package com.happycodelucky.backgrounder.jvm

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
import com.russhwolf.settings.PreferencesSettings
import java.util.prefs.Preferences

/**
 * Constructor-injection wiring for the JVM [Backgrounder] graph.
 *
 * Mirrors `MacOSBackgrounderBuilder` — the JVM graph is the same shape because
 * both schedulers are in-process:
 *
 *  - no state store (the scheduler holds its own job map),
 *  - no coroutine-bridge object (the scheduler launches its own coroutines),
 *  - no plist / manifest validation step (no OS registry exists on the JVM).
 *
 * Persistence is `java.util.prefs.Preferences` via multiplatform-settings'
 * [PreferencesSettings] — the JVM analogue of the `NSUserDefaults` suite the
 * Apple builders use, under the same node name. Only the ephemeral-task mirror
 * lives there; schedules themselves are in-process and die with the JVM (see
 * `CoroutineBackedScheduler`'s guarantees).
 *
 * `start()` only needs to clear ephemeral entries from our mirror;
 * `shutdown()` cancels the scheduler's [kotlinx.coroutines.SupervisorJob]-rooted
 * scope and the instant runner's.
 */
internal object JvmBackgrounderBuilder {
    fun build(eventListener: BackgrounderEventListener): Backgrounder {
        val settings =
            PreferencesSettings(Preferences.userRoot().node("com.happycodelucky.backgrounder.shared"))
        val ephemeral = EphemeralRegistry(settings)
        val registry = WorkerRegistry()

        // Pre-execution network gate. Driven by `Reachability.shared` —
        // process-lifetime singleton. Tests override the singleton via
        // the `:reachable-testing` artifact's `withFakeReachability { }`
        // install hook; no Backgrounder-side parameter is needed.
        //
        // Warm up the platform observer now by reading isReachable once —
        // Reachability.shared lazily constructs its platform monitor on
        // first access; forcing it here keeps the first scheduled worker
        // out of the cold path.
        val gate = ReachabilityGate(Reachability.shared)
        Reachability.shared.isReachable // discarded — read is the warmup side-effect

        // Shared emitter — feeds both the v1 listener (for the four v1-shape
        // events) and the SharedFlow exposed via Backgrounder.events().
        val emitter = MonitorEventEmitter(eventListener)

        val scheduler =
            CoroutineBackedScheduler(
                registry = registry,
                ephemeral = ephemeral,
                emitter = emitter,
                gate = gate,
            )

        // The instant runner is owned by Backgrounder, not Scheduler — see plan
        // §"Why a separate runner type rather than reusing Scheduler". The JVM
        // runner runs the lambda directly on a library scope; no platform
        // scheduler is involved (shared with macOS via commonMain).
        val pendingInstantCalls = PendingInstantCalls()
        val instantRunner = LibraryScopeInstantRunner(pendingInstantCalls, platformLabel = "JVM")

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
                        // The JVM has no OS-level "registered task ids" concept; the
                        // ephemeral sweep just clears our mirror — same as macOS.
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
