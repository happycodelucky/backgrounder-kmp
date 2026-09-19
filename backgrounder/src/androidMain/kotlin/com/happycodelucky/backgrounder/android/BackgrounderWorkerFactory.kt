package com.happycodelucky.backgrounder.android

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.happycodelucky.backgrounder.MonitorEventEmitter
import com.happycodelucky.backgrounder.PendingInstantCalls
import com.happycodelucky.backgrounder.WorkerRegistry

/**
 * Hand-rolled `WorkerFactory` that constructs [RegistryDispatchWorker] with
 * the runtime dependencies it needs (a [WorkerRegistry] and a
 * [MonitorEventEmitter]) — replaces `koin-androidx-workmanager`'s
 * `KoinWorkerFactory` (plan §"DI-free initialization" §2.3).
 *
 * Returns `null` for any other worker class so `DelegatingWorkerFactory` can
 * chain — the standard `WorkerFactory` contract documented by AndroidX. This
 * lets the consumer compose this factory with their own (e.g. Hilt's
 * `HiltWorkerFactory`) via `DelegatingWorkerFactory.addFactory(...)`.
 *
 * The user installs this via `Configuration.Provider.workManagerConfiguration`
 * (see `BackgroundTaskManager.androidWorkerFactory()` for the user-facing accessor).
 */
internal class BackgrounderWorkerFactory(
    private val registry: WorkerRegistry,
    private val emitter: MonitorEventEmitter,
    private val readyGate: kotlinx.atomicfu.AtomicBoolean,
    private val pendingInstantCalls: PendingInstantCalls,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        // The class-name comparison is intentional. WorkerFactory contract:
        // return null for classes we don't recognise so DelegatingWorkerFactory
        // can defer to the next factory. Comparing against `class.java.name`
        // is the documented pattern; `Class.forName(workerClassName)` would
        // load the class on every dispatch which we don't need.
        return when (workerClassName) {
            RegistryDispatchWorker::class.java.name -> {
                RegistryDispatchWorker(appContext, workerParameters, registry, emitter, readyGate)
            }

            InstantDispatchWorker::class.java.name -> {
                InstantDispatchWorker(appContext, workerParameters, pendingInstantCalls)
            }

            else -> {
                null
            }
        }
    }
}
