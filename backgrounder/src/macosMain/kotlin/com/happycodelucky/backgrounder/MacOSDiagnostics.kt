package com.happycodelucky.backgrounder

/**
 * macOS `actual` for [platformDiagnostics]. `NSBackgroundActivityScheduler` is
 * in-process — there's no `Info.plist` entry to validate, no Background App
 * Refresh switch, no WorkManager. The only check we share with the other
 * platforms is "has [BackgroundTaskManager.start] been called yet?".
 */
internal actual fun platformDiagnostics(
    registry: WorkerRegistry,
    isStarted: Boolean,
): PlatformDiagnostics {
    @Suppress("UNUSED_PARAMETER", "unused")
    val unused = registry
    if (!isStarted) {
        return PlatformDiagnostics(listOf(PlatformDiagnostic.RegistryNotSealed))
    }
    return PlatformDiagnostics.Healthy
}
