package com.happycodelucky.backgrounder

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * A configuration or environment issue the library has detected that could
 * stop scheduled work from running.
 *
 * Surfaced from [Backgrounder.diagnostics] so apps can render an "is my
 * background work going to run?" health check at app launch. Best-effort
 * per platform — each case carries which platform produced it.
 *
 * SKIE renders this as a Swift `enum` via `onEnum(of:)`.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "PlatformDiagnostic")
public sealed interface PlatformDiagnostic {
    /**
     * **iOS only.** A registered task id is missing from the app's
     * `BGTaskSchedulerPermittedIdentifiers` array in `Info.plist`. iOS will
     * silently refuse to register the OS handler for it, and scheduled work
     * for this id will never fire.
     */
    public data class MissingInfoPlistEntry(
        public val taskId: String,
    ) : PlatformDiagnostic

    /**
     * **iOS only.** The user has disabled Background App Refresh for this
     * app (Settings → General → Background App Refresh, or system-wide).
     * `BGAppRefreshTaskRequest` will silently never fire; `BGProcessingTaskRequest`
     * may still run but with reduced priority.
     */
    public data object BackgroundRefreshDisabled : PlatformDiagnostic

    /**
     * **Android only.** `WorkManager.getInstance()` has not been initialized
     * with a configuration that includes the library's
     * [BackgroundWorkerFactory] equivalent. Workers fired before init will
     * be unable to construct.
     */
    public data object WorkManagerNotInitialized : PlatformDiagnostic

    /**
     * The library's [WorkerRegistry] has not been sealed — [Backgrounder.start]
     * has not been called yet. Until it is, the platform schedulers cannot
     * register OS handlers (iOS) or accept enqueued work (Android).
     */
    public data object RegistryNotSealed : PlatformDiagnostic
}

/**
 * Result of [Backgrounder.diagnostics] — list of currently-active
 * [PlatformDiagnostic]s. An empty list means the library believes the
 * environment is configured correctly.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "PlatformDiagnostics")
public data class PlatformDiagnostics(
    public val diagnostics: List<PlatformDiagnostic>,
) {
    public val isHealthy: Boolean get() = diagnostics.isEmpty()

    public companion object {
        public val Healthy: PlatformDiagnostics = PlatformDiagnostics(emptyList())
    }
}

/**
 * Platform-specific environment check. Implemented in each platform's
 * source set:
 *  - iOS reads `BGTaskSchedulerPermittedIdentifiers` from `Info.plist` and
 *    queries `UIApplication.backgroundRefreshStatus`.
 *  - Android queries `WorkManager.isInitialized()`.
 *  - macOS / JVM have no equivalent OS-level config to check; they return
 *    [PlatformDiagnostics.Healthy] plus any common diagnostics (e.g.
 *    registry-not-sealed).
 *
 * The receiver supplies the engine's registry / start state; per-platform
 * implementations consult OS APIs and produce the platform-specific cases.
 */
internal expect fun platformDiagnostics(
    registry: WorkerRegistry,
    isStarted: Boolean,
): PlatformDiagnostics
