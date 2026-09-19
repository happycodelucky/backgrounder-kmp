package com.happycodelucky.backgrounder.android

import android.app.Application
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import com.happycodelucky.backgrounder.BackgroundTaskManager
import com.happycodelucky.backgrounder.WorkerRegistry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Process-wide bridge from WorkManager back to the live [BackgroundTaskManager].
 *
 * WorkManager instantiates workers reflectively through the `WorkerFactory`
 * installed via `Configuration.Provider`, so that factory needs a static way
 * to reach the registry. There is exactly one live [BackgroundTaskManager] per process
 * (see `SharedBackgroundTaskManager`), which is why this is a single slot rather than
 * an instance-keyed map.
 */
internal object AndroidBackgrounderInternals {
    // MUST NOT call suspend functions inside this block — see CLAUDE.md §3.
    private val lock = SynchronizedObject()

    private class Attached(
        val backgrounder: BackgroundTaskManager,
        val factory: BackgrounderWorkerFactory,
        val application: Application,
        val registry: WorkerRegistry,
    )

    private var attached: Attached? = null

    fun attach(
        backgrounder: BackgroundTaskManager,
        factory: BackgrounderWorkerFactory,
        application: Application,
        registry: WorkerRegistry,
    ): Unit =
        synchronized(lock) {
            attached = Attached(backgrounder, factory, application, registry)
        }

    /** Called from `shutdown()`; the next `configure` re-attaches. */
    fun detach(): Unit =
        synchronized(lock) {
            attached = null
        }

    fun workerFactory(backgrounder: BackgroundTaskManager): WorkerFactory =
        synchronized(lock) {
            val current = attached
            if (current == null || current.backgrounder !== backgrounder) {
                error(
                    "BackgroundTaskManager has not been attached. This instance is not the live BackgroundTaskManager.shared — " +
                        "construct via BackgroundTaskManager.configure(application) or use BackgroundTaskManager.shared.",
                )
            }
            current.factory
        }

    /**
     * `null` when [registry] belongs to no live instance (diagnostics can't
     * tell), otherwise whether WorkManager can be resolved.
     */
    fun isWorkManagerInitialized(registry: WorkerRegistry): Boolean? =
        synchronized(lock) {
            val current = attached
            if (current == null || current.registry !== registry) return@synchronized null
            @Suppress("DEPRECATION")
            WorkManager.isInitialized() ||
                runCatching { WorkManager.getInstance(current.application) }.isSuccess
        }
}
