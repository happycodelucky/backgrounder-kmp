package com.happycodelucky.backgrounder.android

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import com.happycodelucky.backgrounder.Backgrounder
import com.happycodelucky.backgrounder.BackgrounderEngine
import com.happycodelucky.backgrounder.BackgrounderEventListener
import com.happycodelucky.backgrounder.EphemeralRegistry
import com.happycodelucky.backgrounder.MonitorEventEmitter
import com.happycodelucky.backgrounder.PendingInstantCalls
import com.happycodelucky.backgrounder.SharedBackgrounder
import com.happycodelucky.backgrounder.WorkerRegistry
import com.russhwolf.settings.SharedPreferencesSettings
import kotlinx.atomicfu.atomic

/**
 * Constructor-injection wiring for the Android [Backgrounder] graph.
 *
 * Plan §"DI-free initialization" §2.3. Two notable differences from the
 * iOS / macOS builders:
 *
 *  1. The `WorkManager` resolution is **lazy** — eager-resolving here would
 *     trigger `WorkManager.getInstance(context)` before the user's
 *     `Configuration.Provider.workManagerConfiguration` had a chance to
 *     install our [BackgrounderWorkerFactory]. The provider is called on
 *     every `schedule` / `cancel` instead.
 *  2. The ephemeral sweep runs **eagerly** at construction time — it must
 *     happen before any worker can dispatch (plan §2.3).
 *
 * Each [Backgrounder] owns its own ready-gate `AtomicBoolean`. The
 * gate is shared with the [BackgrounderWorkerFactory] (and therefore with
 * any [RegistryDispatchWorker] WorkManager spins up via that factory), so
 * the worker's "fired before markReady" check sees the same flag the
 * builder controls. `start()` flips it `true`; the gate persists for the
 * process lifetime of this `Backgrounder`.
 */
internal object AndroidBackgrounderBuilder {
    fun build(
        application: Application,
        eventListener: BackgrounderEventListener,
        suppliedWorkManager: WorkManager?,
    ): Backgrounder {
        val settings =
            SharedPreferencesSettings(
                application.getSharedPreferences("backgrounder.prefs", Context.MODE_PRIVATE),
            )
        val ephemeral = EphemeralRegistry(settings)
        val registry = WorkerRegistry()

        // Eager ephemeral sweep — first thing we do, before any worker can fire.
        // Snapshots the ephemeral ids now; cancels them inside start(), once
        // Configuration.Provider has been able to install our WorkerFactory.
        // (Construction may run from the androidx.startup initializer, before
        // Application.onCreate — touching WorkManager there would lock in the
        // default Configuration.)
        val sweep = AndroidEphemeralSweep(application, ephemeral)

        // Per-instance ready gate. Replaces the old top-level
        // `AndroidEphemeralReady` singleton: each Backgrounder holds its own,
        // shared with its WorkerFactory + the dispatch worker via constructor
        // injection. `start()` flips it true.
        val readyGate = atomic(false)

        // Lazy: do NOT call WorkManager.getInstance(application) here. If we
        // did, it would lock the WorkManager configuration to whatever's
        // currently registered — but the user installs our factory via
        // `Configuration.Provider`, which only runs the FIRST time anyone
        // calls `getInstance`. Resolve at use-time instead.
        val workManagerProvider: () -> WorkManager = { suppliedWorkManager ?: WorkManager.getInstance(application) }

        // Single emitter — shared between the WorkManager-side scheduler and
        // the per-dispatch RegistryDispatchWorker (via the factory) so every
        // emit site funnels through one fan-out point. The four v1 listener
        // callbacks are dispatched from inside the emitter; richer
        // MonitorEvent cases land only on the SharedFlow.
        val emitter = MonitorEventEmitter(eventListener)

        val scheduledTaskQuery = AndroidScheduledTaskQuery(workManagerProvider, ephemeral)
        val scheduler =
            WorkManagerScheduler(
                workManagerProvider = workManagerProvider,
                ephemeral = ephemeral,
                emitter = emitter,
                scheduledTaskQuery = scheduledTaskQuery,
            )

        // The instant-runNow path: a process-singleton single-slot map plus the
        // WorkManager-backed runner. The factory below also routes
        // `InstantDispatchWorker` instantiations to construct it with the
        // same `PendingInstantCalls` instance.
        val pendingInstantCalls = PendingInstantCalls()
        val instantRunner = WorkManagerInstantRunner(workManagerProvider, pendingInstantCalls)

        val factory = BackgrounderWorkerFactory(registry, emitter, readyGate, pendingInstantCalls)

        val backgrounder =
            Backgrounder(
                BackgrounderEngine(
                    registry = registry,
                    scheduler = scheduler,
                    instantRunner = instantRunner,
                    emitter = emitter,
                    onStart = {
                        sweep.run()
                        // Plan §1.1: `start()` flips the ready gate so workers
                        // that were enqueued (but blocked by the
                        // ephemeral-not-ready backstop) can now run.
                        readyGate.value = true
                    },
                    onShutdown = {
                        // WorkManager owns its own dispatch scope (Plan §1.1 /
                        // SchedulerGuarantees); only the process-wide bridge
                        // to it needs releasing.
                        AndroidBackgrounderInternals.detach()
                    },
                ),
            )
        // Claim the process-wide slot first: if another instance is live this
        // throws before WorkManager is handed a second factory.
        SharedBackgrounder.install(backgrounder)
        AndroidBackgrounderInternals.attach(backgrounder, factory, application, registry)
        return backgrounder
    }
}
