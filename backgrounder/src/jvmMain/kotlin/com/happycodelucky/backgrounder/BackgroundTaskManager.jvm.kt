package com.happycodelucky.backgrounder

import com.happycodelucky.backgrounder.jvm.JvmBackgrounderBuilder

/**
 * JVM (desktop / server) factory for [BackgroundTaskManager].
 *
 * Hold the returned instance for the lifetime of the process — typically as a
 * property on your application's composition root:
 *
 * ```kotlin
 * val backgrounder = BackgroundTaskManager.create()
 * backgrounder.register(SyncWorker.ID) { SyncWorker(repo = …) }
 * backgrounder.start()
 * ```
 *
 * Scheduling on the JVM is in-process (library-owned coroutines — there is no
 * OS background scheduler to delegate to), so schedules do not survive the
 * process: re-schedule from your app's init path at each launch, and call
 * [BackgroundTaskManager.shutdown] on exit (e.g. from a shutdown hook or your UI
 * framework's teardown) to cancel the scheduler's coroutine scope cleanly.
 *
 * @param eventListener observability hook for `onScheduled`, `onStarted`,
 *   `onCompleted`, `onCancelled`. Defaults to [BackgrounderEventListener.Noop].
 *
 * @return a constructed but not-yet-started [BackgroundTaskManager]. Call
 *   [BackgroundTaskManager.register] for every task id, then [BackgroundTaskManager.start].
 *
 * The pre-execution `WorkConstraints.networkRequired` gate reads from
 * `Reachability.shared` (process-lifetime singleton). Tests install a
 * `FakeReachability` via the `:reachable-testing` artifact's
 * `withFakeReachability { … }` helper.
 */
public fun BackgroundTaskManager.Companion.create(
    eventListener: BackgrounderEventListener = BackgrounderEventListener.Noop,
): BackgroundTaskManager = JvmBackgrounderBuilder.build(eventListener)

/** The JVM needs no configuration, so `BackgroundTaskManager.shared` builds on first access. */
internal actual fun createDefaultBackgrounder(): BackgroundTaskManager = BackgroundTaskManager.create()
