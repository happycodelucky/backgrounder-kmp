package com.happycodelucky.backgrounder

import com.happycodelucky.backgrounder.ios.IOSBackgrounderBuilder
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * iOS factory for [Backgrounder].
 *
 * Hold the returned instance for the lifetime of the app — typically as a
 * stored property on `AppDelegate`. The Swift call site reads:
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
@Throws(IllegalArgumentException::class)
public fun Backgrounder.Companion.create(
    tickIdentifier: String,
    eventListener: BackgrounderEventListener = BackgrounderEventListener.Noop,
): Backgrounder = IOSBackgrounderBuilder.build(tickIdentifier, eventListener)
