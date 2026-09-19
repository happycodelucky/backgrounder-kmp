package com.happycodelucky.backgrounder

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Optional lifecycle hook for app-level metrics — the imperative,
 * callback-shaped delivery channel for the four v1 events
 * (`onScheduled` / `onStarted` / `onCompleted` / `onCancelled`).
 *
 * Kermit handles structured logging by default. This interface surfaces the
 * same events for "is iOS actually running my tasks?" dashboards.
 * Implementations **must not block or throw** — they're called inline on the
 * dispatcher running the worker.
 *
 * **Prefer [Backgrounder.events] for new code.** The
 * `SharedFlow<MonitorEvent>` exposed there carries the same four events plus
 * the richer events the listener does not cover (deferral, skip, attempt
 * failure cause, retry scheduling, library error, schedule replacement). Both
 * channels are fed by a single internal emit point (see
 * [MonitorEventEmitter]) so the listener and the flow stay in lockstep.
 *
 * Pass an implementation to the per-platform `Backgrounder.create(...)`
 * factory; the default is [Noop].
 *
 * `@OptIn(ExperimentalObjCName::class)`: Swift-rename annotation so callbacks
 * read like Swift selectors at the iOS / macOS boundary (CLAUDE.md §8).
 */
@OptIn(ExperimentalObjCName::class)
public interface BackgrounderEventListener {
    /** Called immediately after [Scheduler.schedule] accepts a [WorkRequest]. */
    @ObjCName(swiftName = "onScheduled")
    public fun onScheduled(
        taskId: String,
        request: WorkRequest,
    )

    /**
     * Called when the platform fires the worker and execution is about to begin.
     *
     * @param attempt 0-based retry counter; 0 on the first invocation.
     */
    @ObjCName(swiftName = "onStarted")
    public fun onStarted(
        taskId: String,
        attempt: Int,
    )

    /**
     * Called when [BackgroundWorker.execute] returns (regardless of [WorkResult]).
     *
     * @param attempt 0-based counter matching the value passed to [onStarted].
     * @param result the outcome returned by [BackgroundWorker.execute].
     */
    @ObjCName(swiftName = "onCompleted")
    public fun onCompleted(
        taskId: String,
        attempt: Int,
        result: WorkResult,
    )

    /** Called when [Scheduler.cancel] or [Scheduler.cancelAll] removes this task. */
    @ObjCName(swiftName = "onCancelled")
    public fun onCancelled(taskId: String)

    public companion object {
        /** No-op listener — used as the default when the user binds nothing. */
        public val Noop: BackgrounderEventListener =
            object : BackgrounderEventListener {
                override fun onScheduled(
                    taskId: String,
                    request: WorkRequest,
                ) = Unit

                override fun onStarted(
                    taskId: String,
                    attempt: Int,
                ) = Unit

                override fun onCompleted(
                    taskId: String,
                    attempt: Int,
                    result: WorkResult,
                ) = Unit

                override fun onCancelled(taskId: String) = Unit
            }
    }
}
