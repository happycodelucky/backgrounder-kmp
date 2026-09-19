package com.happycodelucky.backgrounder.monitor

import com.happycodelucky.backgrounder.MonitorEvent
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Observes every [MonitorEvent] emitted by an attached
 * `com.happycodelucky.backgrounder.BackgroundTaskManager`.
 *
 * Multiple monitors can attach to one `BackgroundTaskManager` simultaneously — each is
 * driven by an independent collector coroutine on the
 * [kotlinx.coroutines.CoroutineScope] passed to `attachMonitor`. There is
 * no fan-in or shared state between monitors; the [Monitor] instance is the
 * unit of subscription, the [AttachedMonitor] returned by `attachMonitor`
 * is the unit of cancellation.
 *
 * Implementations:
 *  - **must not block** inside [onEvent] — the collector coroutine pauses
 *    while [onEvent] runs, and a slow monitor backs up against the core's
 *    `SharedFlow(extraBufferCapacity=64, DROP_OLDEST)` buffer. The library
 *    deliberately drops oldest unread events under sustained back-pressure
 *    (CLAUDE.md §3 — scheduler dispatch must not be pinned by observers).
 *    If you need to do heavy work per event, launch a child coroutine and
 *    forward the event to a `Channel` you drain elsewhere.
 *  - **may suspend** inside [onEvent] — the signature is `suspend` so
 *    forwarding to a `Channel.send`, a Ktor request, or any other
 *    suspending sink is straightforward.
 *  - **must not throw** uncaught — exceptions propagate through the
 *    collector coroutine and may terminate the subscription. Catch and
 *    log inside [onEvent] if your work can fail.
 *
 * SKIE bridges this as a Swift protocol with an `async` requirement.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "Monitor")
public interface Monitor {
    /**
     * Called for every event emitted by the attached `BackgroundTaskManager` while the
     * monitor is attached. Ordering matches the producer's emission order
     * (best-effort per task id; see [MonitorEvent]).
     */
    @ObjCName(swiftName = "onEvent")
    public suspend fun onEvent(event: MonitorEvent)
}
