package com.happycodelucky.backgrounder

import com.happycodelucky.backgrounder.android.AndroidBackgrounderInternals

/**
 * Android `actual` for [platformDiagnostics].
 *
 * Two checks:
 *  1. Has `WorkManager` been initialised? If the consumer hasn't installed
 *    the library's [com.happycodelucky.backgrounder.android.BackgrounderWorkerFactory]
 *    via `Configuration.Provider.workManagerConfiguration`, enqueued
 *    workers will fail to instantiate.
 *  2. Has the [WorkerRegistry] been sealed (i.e. [BackgroundTaskManager.start] called)?
 *
 * If we cannot determine the WorkManager state (no application reference
 * paired with the registry — defensive fallback), we omit the diagnostic
 * rather than emit a false negative.
 */
internal actual fun platformDiagnostics(
    registry: WorkerRegistry,
    isStarted: Boolean,
): PlatformDiagnostics {
    val findings = mutableListOf<PlatformDiagnostic>()

    if (!isStarted) {
        findings.add(PlatformDiagnostic.RegistryNotSealed)
    }

    val workManagerOk = AndroidBackgrounderInternals.isWorkManagerInitialized(registry)
    if (workManagerOk == false) {
        findings.add(PlatformDiagnostic.WorkManagerNotInitialized)
    }

    return PlatformDiagnostics(findings)
}
