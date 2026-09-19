package com.happycodelucky.backgrounder.monitor

import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.BackgroundTaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

private val log = Logger.withTag("Backgrounder/Monitor")

/**
 * Attach a [Monitor] to [BackgroundTaskManager.events]. The collector coroutine
 * runs on [scope]; cancelling either [scope] or the returned
 * [AttachedMonitor] tears the subscription down.
 *
 * **Scope ownership** (CLAUDE.md §3). The collector is a child of [scope],
 * so cancellation propagates through structured concurrency. Pick a scope
 * tied to a sensible lifetime — typically a ViewModel scope, an
 * app-level scope, or a test's scope. Never `GlobalScope`.
 *
 * **Multiple monitors.** Each call attaches an independent collector;
 * monitors do not share state or back-pressure. The core's
 * `MutableSharedFlow` is hot and non-replaying — late attachers see
 * events emitted after their attach point, never before.
 *
 * **Exceptions.** A [Monitor.onEvent] that throws does **not** cancel the
 * subscription, and never propagates into [scope] — a misbehaving monitor
 * must not take down the caller's sibling jobs. The event is dropped and
 * the error logged via Kermit. Cancellation (of [scope] or the returned
 * [AttachedMonitor]) still tears the collector down normally.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "attach")
public fun BackgroundTaskManager.attachMonitor(
    scope: CoroutineScope,
    monitor: Monitor,
): AttachedMonitor {
    val job =
        scope.launch {
            events().collect { event ->
                try {
                    monitor.onEvent(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // Library boundary: never let user monitor code poison the
                    // caller-supplied scope. Drop the event, keep collecting.
                    log.e(t) { "Monitor.onEvent threw; event dropped, subscription continues" }
                }
            }
        }
    return AttachedMonitor(job)
}
