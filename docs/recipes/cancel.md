# Cancel work

There are two cancel surfaces and they mean different things.

```kotlin
// Unified — cancels EVERYTHING for the task id: scheduled work AND in-flight runNow.
val outcome: CancelOutcome = backgrounder.cancel(SyncWorker.ID)

when (outcome) {
    is CancelOutcome.Cancelled -> log.i { "cleared ${outcome.pendingCleared} pending request(s)" }
    CancelOutcome.NoSuchTask    -> log.i { "no such pending task" }
}
```

| Method | Cancels scheduled requests | Cancels in-flight scheduled worker | Cancels in-flight `runNow` |
| --- | :---: | :---: | :---: |
| `BackgroundTaskManager.cancel(taskId)`  | ✓ | ✓ | ✓ |
| `BackgroundTaskManager.cancelAll()`     | ✓ (all task ids) | ✓ (all task ids) | — |

Use `BackgroundTaskManager.cancel(taskId)` unless you specifically need scheduled-only semantics (i.e. you want `cancelAll()` which does not touch in-flight `runNow` calls). The `pendingCleared` count on the returned `CancelOutcome.Cancelled` reflects the platform-reported scheduled count — in-flight `runNow` cancellations are not added to it (the count's meaning stays consistent with v1).

To cancel everything Backgrounder has scheduled (does not touch in-flight `runNow`):

```kotlin
backgrounder.cancelAll()
```

`cancelAll()` only cancels work this library scheduled (Android: matched by the canonical `_backgrounder` tag; iOS: enumerated from the library's state store). Other WorkManager / `BGTaskScheduler` work in your app is unaffected.

## What "cancel" means per platform

For **scheduled** work, the OS-imposed primitive decides whether an in-flight worker can be interrupted:

| Platform | Does `BackgroundTaskManager.cancel(taskId)` interrupt a running scheduled worker? |
| -------- | --------------------------------------------- |
| Android  | **Yes** — `WorkManager.cancelUniqueWork` triggers `onStopped`, the coroutine job is cancelled. |
| iOS      | **No** — `BGTaskScheduler.cancel(taskRequestWithIdentifier:)` only kills *pending* requests. A worker mid-execution finishes whatever it was doing. |
| macOS    | **Yes** — `NSBackgroundActivityScheduler.invalidate()` interrupts the running block. |

For **in-flight `runNow`**, `BackgroundTaskManager.cancel(taskId)` always cancels the lambda on every platform — the deferred completes with `CancellationException` and the caller's `await` rethrows. (`runNow` runs on the calling coroutine context with a platform-specific runway, so the cancellation path is purely in-process; no platform-scheduler involvement.)

The iOS gap on scheduled work is reflected in `BackgroundTaskManager.guarantees().cancelsInFlight = false`. If your UX shows a "Cancel" button for *scheduled* work, branch on this:

```kotlin
val cancelButton = if (backgrounder.guarantees().cancelsInFlight) {
    Button("Cancel sync")                     // stops in-flight on Android / macOS
} else {
    Button("Cancel future syncs")             // honest about iOS
}
```

## Cancelling a periodic

For periodic schedules, `cancel(taskId)` clears the persisted `active` flag. If a handler is *already running* on iOS, it sees `active = false` on completion and skips the resubmit step — so the next interval won't fire. The current run still completes.
