# Inspect scheduled work

```kotlin
val tasks: List<ScheduledTask> = backgrounder.scheduled()

tasks.forEach { task ->
    println("${task.taskId} → ${task.kind} ${task.state} attempt=${task.attempt}")
    task.pendingPredicates.forEach { predicate -> println("  waiting on: $predicate") }
}
```

`scheduled()` returns a snapshot — a list of every task the library knows about. It's `suspend` because Android's `WorkManager.getWorkInfos` returns a `ListenableFuture` and iOS's `BGTaskScheduler.getPendingTaskRequests` is callback-shaped; the suspend signature lets us await both cleanly.

## Fields on `ScheduledTask`

| Field                | Meaning                                                                                        |
| -------------------- | ---------------------------------------------------------------------------------------------- |
| `taskId`             | The task id you scheduled.                                                             |
| `kind`               | `OneTime` or `Periodic`.                                                                       |
| `state`              | One of `Pending`, `Running`, `Backoff`, `Blocked`. Best-effort per platform — see below.       |
| `nextRunHint`        | Best-effort `Instant` of the next scheduled run. May be `null`.                                |
| `attempt`            | Library-tracked retry attempt counter (within a cycle for periodic).                           |
| `ephemeral`          | True if this task was scheduled with `ephemeral = true`.                                       |
| `pendingPredicates`  | Why this task isn't running yet — see [Why work is pending](../concepts/pending-predicates.md). |

## Per-platform state mapping

- **Android.** `state` is derived directly from `WorkInfo` — `RUNNING` → `Running`, `ENQUEUED` → `Pending` (or `Backoff` if `runAttemptCount > 0`), `BLOCKED` → `Blocked`.
- **iOS.** The library does not directly observe a worker between handler-fire and `setTaskCompletedSuccess`, so that span is reported as `Pending` rather than `Running`. `Backoff` means the library's state store says active + iOS has no pending request + attempt counter is non-zero. `Blocked` is rare (typically force-quit aftermath before the resurrection sweep).
- **macOS.** `Backoff` if the library's last result was `Retry`; otherwise `Pending`.

## Reactive event stream

The library exposes a hot `SharedFlow<MonitorEvent>` for every scheduling, dispatch, deferral, completion, retry, cancellation, and library-internal error — see [the monitor recipe](monitor.md) for the full event vocabulary. Inspector UIs typically combine a snapshot poll (the `scheduled()` example above) with the event stream:

```kotlin
backgrounder.events()
    .filterIsInstance<MonitorEvent.WorkCompleted>()
    .collect { e -> println("${e.taskId} finished in ${e.runtime}: ${e.result}") }
```

`MonitorEvent` is a sealed interface — Swift consumers get an exhaustive `enum` via `onEnum(of:)`, and the same on Kotlin via `when`.

## Registered factories and platform diagnostics

```kotlin
// Which factories own which task ids (for inspector attribution).
backgrounder.registeredFactories().forEach { d ->
    println("${d.factoryId ?: "<anonymous>"} → ${d.taskIds}")
}

// Is the environment configured correctly?
val diag = backgrounder.diagnostics()
if (!diag.isHealthy) {
    diag.diagnostics.forEach { println("⚠ $it") }
}
```

`diagnostics()` reports cross-platform issues like `RegistryNotSealed`, iOS-only ones like `MissingInfoPlistEntry(taskId)`, and Android-only ones like `WorkManagerNotInitialized`. Run it at app launch as a health check.
