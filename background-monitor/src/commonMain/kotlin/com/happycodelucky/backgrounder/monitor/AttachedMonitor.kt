package com.happycodelucky.backgrounder.monitor

import kotlinx.coroutines.Job
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Handle to a live monitor subscription.
 *
 * Returned by `BackgroundTaskManager.attachMonitor(...)`. Call [detach] to stop
 * delivering events to the monitor without cancelling the surrounding
 * [kotlinx.coroutines.CoroutineScope]; or let the surrounding scope cancel
 * naturally (e.g. ViewModel teardown) and the subscription tears down with
 * it. [detach] is idempotent — repeat calls are no-ops.
 *
 * `isActive` mirrors the underlying collector coroutine's job — `false`
 * means the subscription has been detached or the parent scope was
 * cancelled.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "AttachedMonitor")
public class AttachedMonitor internal constructor(
    private val job: Job,
) {
    /** `true` while the monitor is still receiving events. */
    @ObjCName(swiftName = "isActive")
    public val isActive: Boolean get() = job.isActive

    /**
     * Stop delivering events to this monitor. Idempotent — second and
     * later calls are no-ops. Does not affect other monitors attached to
     * the same `BackgroundTaskManager`.
     */
    @ObjCName(swiftName = "detach")
    public fun detach() {
        job.cancel()
    }
}
