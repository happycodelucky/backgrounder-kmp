package com.happycodelucky.backgrounder.monitor

import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.BackgroundTaskManager
import com.happycodelucky.backgrounder.PlatformDiagnostics
import com.happycodelucky.backgrounder.ScheduledTask
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Duration

/**
 * Polls the inspector APIs ([BackgroundTaskManager.scheduled] /
 * [BackgroundTaskManager.diagnostics]) on an interval and surfaces the latest
 * snapshot through a [StateFlow] pair.
 *
 * Inspector UIs typically want both the *event stream* (via [Monitor]) and
 * the *current state* (the inspector APIs) on a refresh cadence. This
 * helper takes care of the polling loop and back-pressure:
 *  - One `StateFlow<List<ScheduledTask>>` reflects the latest `scheduled()`
 *    response.
 *  - One `StateFlow<PlatformDiagnostics>` reflects the latest
 *    `diagnostics()` response.
 *  - The poll coroutine sleeps for [interval] between polls; intervals
 *    shorter than ~250 ms are discouraged (the underlying iOS query is
 *    callback-shaped and the Android query reads `WorkInfo` over an IPC).
 *
 * **Scope ownership.** The poller's loop runs on the [CoroutineScope]
 * passed to [start]. Cancel the scope to stop polling. The flows continue
 * to hold their last-emitted value indefinitely; consumers reading after
 * cancellation see a frozen view.
 *
 * **Initial state.** Before the first successful poll completes, both
 * flows emit `null` / [PlatformDiagnostics.Healthy] respectively. After
 * that, every poll either updates the flow value or leaves it unchanged
 * if nothing changed (StateFlow's distinct-equal de-duplication).
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "SnapshotPoller")
public class SnapshotPoller(
    private val backgrounder: BackgroundTaskManager,
    private val interval: Duration,
) {
    private val _scheduled = MutableStateFlow<List<ScheduledTask>?>(null)

    /**
     * Most recent `scheduled()` snapshot, or `null` until the first poll
     * completes. SKIE bridges this as `AsyncSequence<[ScheduledTask]?>`.
     */
    @ObjCName(swiftName = "scheduled")
    public val scheduled: StateFlow<List<ScheduledTask>?> get() = _scheduled.asStateFlow()

    private val _diagnostics = MutableStateFlow(PlatformDiagnostics.Healthy)

    /**
     * Most recent `diagnostics()` snapshot. Defaults to
     * [PlatformDiagnostics.Healthy] until the first poll.
     */
    @ObjCName(swiftName = "diagnostics")
    public val diagnostics: StateFlow<PlatformDiagnostics> get() = _diagnostics.asStateFlow()

    private val log = Logger.withTag("Backgrounder/Monitor/SnapshotPoller")

    // Single-slot loop handle. `atomic` (not a plain `var`): start()/stop() are
    // public API callable from any thread (ViewModel recreation, config change),
    // and the read-cancel-write sequence must claim the slot atomically or a
    // racing start() leaks an uncancellable loop.
    private val loop = atomic<Job?>(null)

    /**
     * Begin polling on [scope]. Idempotent — calling twice is treated as a
     * restart on the new scope; the previous loop is cancelled. Returns
     * the [Job] driving the loop so callers can join or cancel it directly
     * if they prefer.
     */
    @ObjCName(swiftName = "start")
    public fun start(scope: CoroutineScope): Job {
        // LAZY so the new loop can't poll before the displaced loop is retired.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                while (isActive) {
                    runCatching {
                        _scheduled.value = backgrounder.scheduled()
                        _diagnostics.value = backgrounder.diagnostics()
                    }.onFailure { t ->
                        if (t is CancellationException) throw t
                        // Swallow and poll again next tick — but never silently:
                        // this is a diagnostics surface, and a quiet failure
                        // defeats its purpose.
                        log.e(t) { "poll failed; retrying next tick" }
                    }
                    delay(interval)
                }
            }
        loop.getAndSet(job)?.cancel()
        job.start()
        return job
    }

    /** Stop polling. Idempotent. */
    @ObjCName(swiftName = "stop")
    public fun stop() {
        loop.getAndSet(null)?.cancel()
    }
}
