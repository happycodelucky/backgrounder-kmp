// ExperimentalForeignApi: required by cinterop FFI types
// (NSBundle, NSArray casts). Stable in practice.
@file:OptIn(ExperimentalForeignApi::class)

package com.happycodelucky.backgrounder

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle

/**
 * iOS `actual` for [platformDiagnostics].
 *
 * Two checks today:
 *  1. Every registered task id must appear in the main bundle's
 *     `BGTaskSchedulerPermittedIdentifiers` `Info.plist` array. iOS will
 *     refuse to install the OS handler for any id missing from that array,
 *     and scheduled work for it will silently never fire.
 *  2. The [WorkerRegistry] must be sealed (i.e. [Backgrounder.start] called).
 *
 * **Not currently checked.** `UIApplication.backgroundRefreshStatus` would
 * tell us whether the user has disabled Background App Refresh — but that
 * accessor requires the main thread (Apple's documented contract), and
 * `diagnostics()` is a sync call invokable from any thread. A future
 * `suspend fun diagnostics()` could hop to the main dispatcher to read it
 * safely; for now we surface only the bundle-level check.
 */
internal actual fun platformDiagnostics(
    registry: WorkerRegistry,
    isStarted: Boolean,
): PlatformDiagnostics {
    val findings = mutableListOf<PlatformDiagnostic>()

    if (!isStarted) {
        findings.add(PlatformDiagnostic.RegistryNotSealed)
    }

    val permitted = readPermittedIdentifiers()
    registry.registeredIds().forEach { id ->
        if (id !in permitted) {
            findings.add(PlatformDiagnostic.MissingInfoPlistEntry(taskId = id))
        }
    }

    return PlatformDiagnostics(findings)
}

/**
 * Read `BGTaskSchedulerPermittedIdentifiers` from the main bundle's
 * Info.plist. Returns an empty set when the key is missing or the array
 * contains non-string entries — the caller treats either case as "no ids
 * permitted", which produces a [PlatformDiagnostic.MissingInfoPlistEntry]
 * for every registered id (the most useful signal at app startup).
 */
private fun readPermittedIdentifiers(): Set<String> {
    val raw = NSBundle.mainBundle.objectForInfoDictionaryKey(KEY) ?: return emptySet()

    @Suppress("UNCHECKED_CAST")
    val list = raw as? List<*> ?: return emptySet()
    return list.filterIsInstance<String>().toSet()
}

private const val KEY = "BGTaskSchedulerPermittedIdentifiers"
