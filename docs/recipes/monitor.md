# Monitor lifecycle events

Every schedule, dispatch, deferral, completion, retry, cancellation, and library-internal error fans out through one stream.

```kotlin
backgrounder.events()
    .collect { event ->
        when (event) {
            is MonitorEvent.Scheduled        -> log.i { "scheduled ${event.taskId}" }
            is MonitorEvent.WorkStarted      -> log.i { "started ${event.taskId} attempt=${event.attempt}" }
            is MonitorEvent.WorkCompleted    -> log.i { "completed ${event.taskId} in ${event.runtime}: ${event.result}" }
            is MonitorEvent.AttemptDeferred  -> log.w { "deferred ${event.taskId}: ${event.reason}" }
            is MonitorEvent.AttemptFailed    -> log.e { "failed ${event.taskId}: ${event.reason}" }
            is MonitorEvent.RetryScheduled   -> log.i { "retry ${event.taskId} attempt=${event.nextAttempt} in ${event.delay}" }
            is MonitorEvent.Cancelled        -> log.i { "cancelled ${event.taskId} via ${event.source}" }
            is MonitorEvent.ScheduleReplaced -> log.i { "replaced ${event.taskId}" }
            is MonitorEvent.Skipped          -> log.w { "skipped ${event.taskId}: ${event.reason}" }
            is MonitorEvent.LibraryError     -> log.e(event.cause) { "library error ${event.taskId}: ${event.message}" }
        }
    }
```

`events()` returns a `SharedFlow<MonitorEvent>` — hot, non-replaying, `replay = 0, extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST`. Late collectors see only events emitted after they subscribe; sustained back-pressure drops the oldest unread events first so the producer is never blocked — scheduler dispatch never pins on observers.

Swift sees this as `AsyncSequence<MonitorEvent>`. Use `onEnum(of:)` for exhaustive switching.

The raw `Throwable` payloads (`LibraryError.cause`, `AttemptFailureReason.FactoryThrew.cause` / `WorkerThrew.cause`) are Kotlin-only — `Throwable` bridges to Swift as an opaque `KotlinThrowable`, so those fields are hidden from the generated header. Swift reads the bridged `causeMessage` / `causeType` strings instead.

## With the `:background-monitor` module

If you want a callback-style attach API (and you don't want to manage the `collect` coroutine yourself), add the optional sibling artifact:

```kotlin
// commonMain
implementation("com.happycodelucky.backgrounder:background-monitor:X.Y.Z")
```

```kotlin
class LoggingMonitor : Monitor {
    override suspend fun onEvent(event: MonitorEvent) {
        log.i { event.toString() }
    }
}

val attached: AttachedMonitor = backgrounder.attachMonitor(viewModelScope, LoggingMonitor())
// later, or just let viewModelScope cancel:
attached.detach()
```

Multiple monitors can attach to one `BackgroundTaskManager` — each runs its own collector coroutine. The unit of subscription is the `Monitor` instance; the unit of cancellation is the returned `AttachedMonitor`.

## Snapshot polling alongside events

Inspector UIs usually want both the event stream *and* the current scheduler state on a refresh cadence. `SnapshotPoller` runs a `scheduled()` + `diagnostics()` poll on an interval and surfaces the results through two `StateFlow`s:

```kotlin
val poller = SnapshotPoller(backgrounder, interval = 1.seconds)
poller.start(viewModelScope)

poller.scheduled.collect { tasks -> /* update task list UI */ }
poller.diagnostics.collect { diag  -> /* update health-check banner */ }
```

Don't poll more often than ~250 ms — Android's `WorkInfo` query is IPC-bound and iOS's `BGTaskScheduler.getPendingTaskRequests` is callback-shaped.

## What can go wrong

- **Slow `Monitor.onEvent`.** The collector coroutine pauses while `onEvent` runs. If you do heavy work (HTTP, database) inside it, the core's 64-slot buffer can overflow under burst load and the oldest unread events get dropped. Forward to a `Channel` you drain elsewhere if your work is slow.
- **Throwing `Monitor.onEvent`.** Uncaught exceptions propagate through the collector's job and may terminate the subscription. Catch and log inside `onEvent` if your work can fail.
- **Late attachers see nothing historical.** The flow doesn't replay. If you need history (e.g. for a debug screen that opens after the fact), persist events on your side as they arrive.
- **`Cancelled` after `BackgroundTaskManager.shutdown()`.** Shutdown does not currently fan-out a synthetic `Cancelled` for each active task — collectors observing the flow see the scope cancel and the flow stop emitting. If you need a "library is shutting down" signal, watch for the scope's cancellation in your own collector.
