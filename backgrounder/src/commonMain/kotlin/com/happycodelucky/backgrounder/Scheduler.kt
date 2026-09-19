package com.happycodelucky.backgrounder

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Internal scheduling surface. Schedules and inspects background work.
 *
 * Not part of the public API — the scheduling verbs are promoted directly
 * onto [BackgroundTaskManager] ([BackgroundTaskManager.schedule], [BackgroundTaskManager.cancelAll],
 * [BackgroundTaskManager.scheduled], [BackgroundTaskManager.guarantees]). This interface is the
 * internal seam the per-platform actuals implement and [BackgrounderEngine]
 * delegates to.
 *
 * Platform actuals:
 * - Android: `WorkManagerScheduler` (backed by Jetpack `WorkManager`).
 * - iOS:     `BGTaskBackedScheduler` (backed by `BGTaskScheduler`).
 * - macOS:   `NSBackgroundActivityBackedScheduler` (backed by Foundation's
 *            `NSBackgroundActivityScheduler`).
 * - JVM:     `CoroutineBackedScheduler` (backed by library-owned coroutines —
 *            the JVM has no OS background scheduler).
 *
 * Most methods are non-suspend because both `WorkManager.enqueue` and
 * `BGTaskScheduler.submit` are non-blocking. [scheduled] is `suspend` because
 * Android's `WorkManager.getWorkInfos` returns a `ListenableFuture` and iOS's
 * `BGTaskScheduler.getPendingTaskRequests` is callback-shaped.
 *
 * The `@ObjCName` annotations are retained on the members so the promoted
 * `BackgroundTaskManager` verbs that delegate here keep a consistent Swift surface.
 *
 * `@OptIn(ExperimentalObjCName::class)`: standard Swift-rename annotation;
 * stable in practice and used by SKIE for boundary refinement.
 */
@OptIn(ExperimentalObjCName::class)
internal interface Scheduler {
    /**
     * Schedule a [WorkRequest]. If a request with the same [WorkRequest.taskId]
     * is already pending, [policy] decides what happens.
     */
    @ObjCName(swiftName = "schedule")
    public fun schedule(
        request: WorkRequest,
        policy: ConflictPolicy = ConflictPolicy.Replace,
    ): ScheduleOutcome

    /**
     * Cancel the pending request for [taskId]. Does not interrupt an
     * already-running worker on iOS — see [SchedulerGuarantees.cancelsInFlight].
     */
    @ObjCName(swiftName = "cancel")
    public fun cancel(taskId: String): CancelOutcome

    /** Cancel every pending request the library knows about. */
    @ObjCName(swiftName = "cancelAll")
    public fun cancelAll(): CancelOutcome

    /**
     * Snapshot of currently-scheduled (pending or running) tasks the library
     * knows about. Not a [Flow] — for a reactive stream, see `Scheduler.observe`
     * (v2). Best-effort per platform.
     *
     * No `@Throws` — SKIE bridges `suspend fun` as Swift `async throws` and
     * routes coroutine cancellation through Swift's native `Task.cancel` /
     * `CancellationError` machinery (CLAUDE.md §8). The platform actuals don't
     * throw documented domain exceptions; if that changes, list them here.
     */
    @ObjCName(swiftName = "scheduled")
    public suspend fun scheduled(): List<ScheduledTask>

    /** What this platform's scheduler actually guarantees. */
    @ObjCName(swiftName = "guarantees")
    public fun guarantees(): SchedulerGuarantees
}
