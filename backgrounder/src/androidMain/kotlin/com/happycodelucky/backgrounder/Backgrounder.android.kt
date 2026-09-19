package com.happycodelucky.backgrounder

import android.app.Application
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import com.happycodelucky.backgrounder.android.AndroidBackgrounderBuilder
import com.happycodelucky.backgrounder.android.AndroidBackgrounderInternals

/**
 * Android entry point: builds the process-wide [Backgrounder] and installs it
 * as `Backgrounder.shared`.
 *
 * Normally you never call this. The library's manifest registers
 * [BackgrounderInitializer] with `androidx.startup`, which calls it before
 * `Application.onCreate`. Call it yourself only if your app removed the
 * `InitializationProvider`, or if you need a [BackgrounderEventListener] or a
 * pre-resolved `WorkManager` — in which case call it first thing in
 * `onCreate`, before anything touches `Backgrounder.shared`.
 *
 * Idempotent with the initializer: if an instance already exists and you
 * pass no listener or `WorkManager`, the existing instance is returned (this
 * is what lets multi-process apps call it unconditionally from `onCreate`).
 * Passing either while an instance exists throws, because the live instance
 * was built without them.
 *
 * ```kotlin
 * class MyApp : Application(), Configuration.Provider {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Backgrounder.shared.register(SyncWorker.ID) { SyncWorker(repo = …) }
 *         Backgrounder.shared.start()
 *     }
 *
 *     override val workManagerConfiguration: Configuration get() =
 *         Configuration.Builder()
 *             .setWorkerFactory(Backgrounder.shared.androidWorkerFactory())
 *             .build()
 * }
 * ```
 *
 * Plan §"DI-free initialization" §1.3 / §2.3 for the full sequencing
 * argument and the `Configuration.Provider` chicken-and-egg note.
 *
 * @param application your `Application`. The library uses it for
 *   `getSharedPreferences`, the ephemeral sweep, and (lazily)
 *   `WorkManager.getInstance(application)`.
 * @param eventListener observability hook. Defaults to
 *   [BackgrounderEventListener.Noop].
 * @param workManager an optional pre-resolved `WorkManager` instance. Use this
 *   only if you have one already and want to skip the lazy
 *   `WorkManager.getInstance(application)` lookup. Most callers leave this
 *   `null` and let the library resolve via `getInstance` on first use.
 * @throws IllegalStateException if a [Backgrounder] is already live and a
 *   listener or `WorkManager` was passed.
 */
@Throws(IllegalStateException::class)
public fun Backgrounder.Companion.configure(
    application: Application,
    eventListener: BackgrounderEventListener = BackgrounderEventListener.Noop,
    workManager: WorkManager? = null,
): Backgrounder {
    val existing = SharedBackgrounder.peek()
    if (existing != null && eventListener === BackgrounderEventListener.Noop && workManager == null) {
        return existing
    }
    return AndroidBackgrounderBuilder.build(
        application = application,
        eventListener = eventListener,
        suppliedWorkManager = workManager,
    )
}

/**
 * Android has no zero-configuration path: building needs an `Application`.
 * Reaching here means neither the startup initializer nor `configure` ran.
 */
internal actual fun createDefaultBackgrounder(): Backgrounder =
    throw IllegalStateException(
        "Backgrounder.shared is not configured. On Android it is populated by the androidx.startup " +
            "InitializationProvider before Application.onCreate; if your manifest removes that provider, " +
            "call Backgrounder.configure(application) in Application.onCreate before first use.",
    )

/**
 * The `WorkerFactory` to install in your `WorkManager.Configuration`. Compose
 * with `DelegatingWorkerFactory` if you also use Hilt's `HiltWorkerFactory` or
 * any other custom factory.
 *
 * ```kotlin
 * override val workManagerConfiguration: Configuration get() =
 *     Configuration.Builder()
 *         .setWorkerFactory(backgrounder.androidWorkerFactory())
 *         .build()
 * ```
 *
 * Or chained with Hilt:
 * ```kotlin
 * Configuration.Builder()
 *     .setWorkerFactory(DelegatingWorkerFactory().apply {
 *         addFactory(hiltWorkerFactory)
 *         addFactory(backgrounder.androidWorkerFactory())
 *     })
 *     .build()
 * ```
 *
 * @throws IllegalStateException if this is not the live `Backgrounder.shared`
 *   instance (e.g. constructed directly via `Backgrounder(engine)` — not
 *   normally possible since the constructor is `internal`, but tests
 *   sometimes find a way).
 */
public fun Backgrounder.androidWorkerFactory(): WorkerFactory = AndroidBackgrounderInternals.workerFactory(this)
