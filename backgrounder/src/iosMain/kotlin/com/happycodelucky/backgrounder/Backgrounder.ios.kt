package com.happycodelucky.backgrounder

import com.happycodelucky.backgrounder.ios.IOSBackgrounderBuilder
import platform.Foundation.NSBundle
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * iOS factory for [Backgrounder]. Installs the result as `Backgrounder.shared`.
 *
 * Optional: `Backgrounder.shared` builds itself on first access using
 * [defaultTickIdentifier] and no listener. Call this first only to supply a
 * listener or your own tick identifier. A second live instance throws.
 * The Swift call site reads:
 *
 * ```swift
 * let backgrounder = Backgrounder.companion.create(
 *     tickIdentifier: "com.example.app.background-tick"
 * )
 * backgrounder.register(taskId: SyncWorker.companion.ID) { /* SyncWorker(…) */ }
 * backgrounder.start()
 * ```
 *
 * @param tickIdentifier the iOS `BGAppRefreshTaskRequest` identifier the
 *   library uses to wake the periodic dispatcher in the background. Required.
 *   Must appear in your app's `Info.plist` under
 *   `BGTaskSchedulerPermittedIdentifiers` — pick something in your app's
 *   reverse-DNS namespace (e.g. `"com.example.app.background-tick"`). Even
 *   though it's used internally by Backgrounder, the identifier lives in
 *   *your* namespace because it surfaces in *your* Info.plist; the library
 *   never invents identifiers in your namespace for you. Validated at
 *   [Backgrounder.start] time and reported with a Kermit error if missing.
 *
 *   Periodic tasks ([WorkRequest.Periodic]) no longer need per-task id
 *   Info.plist entries — the tick identifier is the only entry they need.
 *   One-shot tasks ([WorkRequest.OneTime]) still register per-task id and
 *   still need their own Info.plist entries.
 *
 * @param eventListener observability hook for `onScheduled`, `onStarted`,
 *   `onCompleted`, `onCancelled`. Defaults to [BackgrounderEventListener.Noop].
 *
 * @return a constructed but not-yet-started [Backgrounder]. Call
 *   [Backgrounder.register] for every task id, then
 *   [Backgrounder.start] before the launch method returns.
 *
 * The pre-execution `WorkConstraints.networkRequired` gate reads from
 * `Reachability.shared` (process-lifetime singleton). Tests install a
 * `FakeReachability` via the `:reachable-testing` artifact's
 * `withFakeReachability { … }` helper, which transparently overrides
 * `Reachability.shared` for the duration of the test block — no
 * Backgrounder-specific test seam is required.
 *
 * `@OptIn(ExperimentalObjCName::class)`: required by SKIE for the
 * Swift-rename annotation. Stable in practice.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "create")
@Throws(IllegalArgumentException::class, IllegalStateException::class)
public fun Backgrounder.Companion.create(
    tickIdentifier: String,
    eventListener: BackgrounderEventListener = BackgrounderEventListener.Noop,
): Backgrounder = IOSBackgrounderBuilder.build(tickIdentifier, eventListener)

/**
 * The tick identifier `Backgrounder.shared` uses when the app never called
 * [create]: the main bundle identifier plus `.backgrounder-tick`
 * (e.g. `dev.example.app.backgrounder-tick`). It must appear in
 * `BGTaskSchedulerPermittedIdentifiers`; the Gradle plugin adds it when
 * `backgrounder.iosBundleIdentifier` is set. Exposed so apps that maintain the
 * plist by hand can read the exact string.
 *
 * `@OptIn(ExperimentalObjCName::class)`: Swift-rename annotation; stable in
 * practice and required by SKIE (CLAUDE.md §8).
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "defaultTickIdentifier")
public fun Backgrounder.Companion.defaultTickIdentifier(): String =
    (NSBundle.mainBundle.bundleIdentifier ?: FALLBACK_TICK_NAMESPACE) + DEFAULT_TICK_SUFFIX

/** Mirrored in the Gradle plugin (`UpdateInfoPlistTask.DEFAULT_TICK_SUFFIX`); keep in sync. */
internal const val DEFAULT_TICK_SUFFIX: String = ".backgrounder-tick"

/** Only reachable in a process with no main-bundle identifier, e.g. some test hosts. */
internal const val FALLBACK_TICK_NAMESPACE: String = "com.happycodelucky.backgrounder"

/** iOS zero-configuration path: [defaultTickIdentifier] and no listener. */
internal actual fun createDefaultBackgrounder(): Backgrounder = Backgrounder.create(tickIdentifier = Backgrounder.defaultTickIdentifier())
