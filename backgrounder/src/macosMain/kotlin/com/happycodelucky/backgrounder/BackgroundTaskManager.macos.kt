package com.happycodelucky.backgrounder

import com.happycodelucky.backgrounder.macos.MacOSBackgrounderBuilder
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * macOS factory for [BackgroundTaskManager].
 *
 * Hold the returned instance for the lifetime of the app — typically as a
 * stored property on `AppDelegate`. The Swift call site reads:
 *
 * ```swift
 * let backgrounder = BackgroundTaskManager.companion.create()
 * backgrounder.register(taskId: SyncWorker.companion.ID) { /* SyncWorker(…) */ }
 * backgrounder.start()
 * ```
 *
 * @param eventListener observability hook for `onScheduled`, `onStarted`,
 *   `onCompleted`, `onCancelled`. Defaults to [BackgrounderEventListener.Noop].
 *
 * @return a constructed but not-yet-started [BackgroundTaskManager]. Call
 *   [BackgroundTaskManager.register] for every task id, then
 *   [BackgroundTaskManager.start] from `applicationDidFinishLaunching`.
 *
 * Call [BackgroundTaskManager.shutdown] from `applicationWillTerminate` to
 * cancel the scheduler's coroutine scope cleanly.
 *
 * The pre-execution `WorkConstraints.networkRequired` gate reads from
 * `Reachability.shared` (process-lifetime singleton). Tests install a
 * `FakeReachability` via the `:reachable-testing` artifact's
 * `withFakeReachability { … }` helper.
 *
 * `@OptIn(ExperimentalObjCName::class)`: required by SKIE for the
 * Swift-rename annotation. Stable in practice.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "create")
public fun BackgroundTaskManager.Companion.create(
    eventListener: BackgrounderEventListener = BackgrounderEventListener.Noop,
): BackgroundTaskManager = MacOSBackgrounderBuilder.build(eventListener)

/** macOS needs no configuration, so `BackgroundTaskManager.shared` builds on first access. */
internal actual fun createDefaultBackgrounder(): BackgroundTaskManager = BackgroundTaskManager.create()
