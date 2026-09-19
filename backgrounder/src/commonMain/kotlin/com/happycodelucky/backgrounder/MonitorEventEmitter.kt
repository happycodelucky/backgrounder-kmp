package com.happycodelucky.backgrounder

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The single emit point for every [MonitorEvent] in the library.
 *
 * Two delivery channels fan out from one call to [emit]:
 *  1. The user-supplied [BackgrounderEventListener] callbacks (synchronous,
 *     on the calling thread — preserves the v1 listener contract).
 *  2. The [SharedFlow] exposed via [events] / [BackgroundTaskManager.events] (`tryEmit`,
 *     non-suspending — a slow collector cannot pin scheduler dispatch).
 *
 * **The legacy `BackgrounderEventListener` only sees the four v1 events**
 * (`onScheduled`, `onStarted`, `onCompleted`, `onCancelled`). The richer event
 * types (deferral, skip, attempt-failed, retry-scheduled, library-error,
 * schedule-replaced) flow only through the [SharedFlow]. Existing v1 listener
 * implementations therefore keep working without recompilation.
 *
 * **Why `tryEmit` only.** CLAUDE.md §3 forbids suspending operations from
 * blocking the dispatcher thread; the suspending [MutableSharedFlow.emit]
 * would let a slow collector backpressure-block the scheduler. The buffer
 * (`extraBufferCapacity = 64`, `BufferOverflow.DROP_OLDEST`) is sized to
 * absorb the natural burst from a `cancelAll()` over a few dozen tasks
 * without dropping; sustained overload drops the oldest unread events
 * first so live collectors see the most recent state.
 *
 * **Owner.** Constructed by [BackgrounderEngine] and held for the engine's
 * lifetime. No shutdown logic — the flow has no scope of its own; cancelling
 * a collector's scope is the collector's responsibility.
 */
internal class MonitorEventEmitter(
    private val legacyListener: BackgrounderEventListener,
) {
    private val sharedFlow: MutableSharedFlow<MonitorEvent> =
        MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Read-only view for external collectors. */
    val events: SharedFlow<MonitorEvent> = sharedFlow.asSharedFlow()

    /**
     * Emit [event] to both channels. Non-suspending.
     *
     * Order of effects:
     *  1. Fan out to the legacy listener for the four v1-shape events.
     *     Wrapped in `runCatching` so a buggy listener cannot crash the
     *     scheduler — the listener contract says it must not throw, but
     *     defence-in-depth here is cheap.
     *  2. `tryEmit` to the shared flow. Returns `true` on success; on
     *     overflow the oldest event is dropped (per buffer policy) and
     *     this returns `true` as well — `false` only when the flow has
     *     no buffer at all (not the case here).
     */
    fun emit(event: MonitorEvent) {
        runCatching { dispatchToLegacy(event) }
        sharedFlow.tryEmit(event)
    }

    private fun dispatchToLegacy(event: MonitorEvent) {
        when (event) {
            is MonitorEvent.Scheduled -> legacyListener.onScheduled(event.taskId, event.request)

            is MonitorEvent.WorkStarted -> legacyListener.onStarted(event.taskId, event.attempt)

            is MonitorEvent.WorkCompleted -> legacyListener.onCompleted(event.taskId, event.attempt, event.result)

            is MonitorEvent.Cancelled -> legacyListener.onCancelled(event.taskId)

            is MonitorEvent.ScheduleReplaced,
            is MonitorEvent.AttemptDeferred,
            is MonitorEvent.Skipped,
            is MonitorEvent.AttemptFailed,
            is MonitorEvent.RetryScheduled,
            is MonitorEvent.LibraryError,
            -> Unit // Not part of the v1 listener surface; flow-only.
        }
    }
}
