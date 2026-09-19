# Run now (instant dispatch)

`runNow` is for "do this work in the background **right now** and let me `await` the typed result." It complements scheduled work — no `WorkConstraints`, no `BackoffPolicy`, no retries, no `register()` step. The lambda *is* the work.

Typical use: the user just hit Save and you want the document persisted in the background, surviving an immediate app-background, with the result piped back into the UI when it returns.

```kotlin
import com.happycodelucky.backgrounder.*

class DocumentVM(private val backgrounder: Backgrounder, private val repo: DocumentRepository) {
    private val saveTaskId = "dev.example.app.save-document"

    suspend fun save(draft: Document): SavedDocument =
        backgrounder.runNow(saveTaskId) {
            repo.save(draft)
        }
}
```

Swift call site:

```swift
let saved: SavedDocument = try await backgrounder.run(taskId: saveTaskId) {
    try await repo.save(draft)
}
```

## What it does, what it doesn't

| | Scheduled (`Backgrounder.schedule`) | Instant (`Backgrounder.runNow`) |
| --- | --- | --- |
| When it runs | When the OS decides constraints are satisfied | Immediately on the calling coroutine |
| Network / charging gating | `WorkConstraints` honored | None — caller checks if needed |
| Retries | `BackoffPolicy`, up to `maxAttempts` | None — thrown exception is terminal |
| Worker source | Registered factory via `Backgrounder.register` | Lambda passed at call site |
| Result | Worker returns `WorkResult`; caller doesn't see it directly | Caller `await`s the typed `R` |
| Survives the caller | Yes — the schedule outlives the calling coroutine | No — caller cancellation cancels the work |

If you need constraint gating, retries, or "schedule and forget," use `Backgrounder.schedule`. If you need "do this and give me back the result," use `runNow`.

## Pre-emption — last call wins

`runNow(taskId, …)` is **pre-emptive** for that task id. Before submitting its own request it cancels:

1. Any other in-flight `runNow` for the same task id — the prior caller's `await` rethrows `CancellationException`.
2. Any pending scheduled request for the same task id.
3. Any in-flight scheduled worker for the same task id (best-effort per platform — see [cancel](cancel.md) for the per-platform caveats).

This is because `runNow` returns a typed `R` to a specific caller; two concurrent invocations would yield ambiguous results. So concurrent calls with the same task id serialize as "newest wins":

```kotlin
// In some VM
suspend fun saveDraft(draft: Document): SavedDocument =
    backgrounder.runNow(saveTaskId) { repo.save(draft) }
//                                    ^ if the user hits Save twice in quick succession,
//                                      the second runNow cancels the first.
```

If you want concurrent independent runs, use distinct task ids.

## Cancellation — structured concurrency

The work runs on the caller's coroutine context (with a platform-specific runway around it on iOS / Android). Cancelling the caller cancels the work:

```kotlin
val job = scope.launch {
    val saved = backgrounder.runNow(saveTaskId) { repo.save(draft) }
    showToast("Saved")
}

// Later
job.cancel()  // → repo.save() observes CancellationException, runNow rethrows, scope unwinds
```

Cancelling externally via `Backgrounder.cancel(taskId)` likewise propagates — see [cancel](cancel.md).

## Exceptions propagate

The lambda's thrown `Throwable` propagates to the caller's `await`:

```kotlin
try {
    val saved = backgrounder.runNow(saveTaskId) {
        repo.save(draft)        // throws NetworkException
    }
} catch (e: NetworkException) {
    showError(e.message)
}
```

The platform layer reports `WorkResult.Failure(message)` to the OS (so iOS / WorkManager don't think the process crashed); the caller sees the original exception. `CancellationException` surfaces in Swift as the native `CancellationError`.

## Platform notes

- **iOS** — `runNow` uses `UIApplication.beginBackgroundTask(withName:expirationHandler:)`, **not** `BGTaskScheduler`. The task id does *not* need to appear in `Info.plist`'s `BGTaskSchedulerPermittedIdentifiers`; it's purely an in-process pre-emption key. iOS grants ~30 seconds of grace if the app backgrounds mid-call.
- **Android** — `runNow` enqueues a unique `OneTimeWorkRequest` under the name `${taskId}::runNow` (won't collide with a scheduled run that uses `${taskId}` as its unique name).
- **macOS / JVM** — `runNow` spawns the lambda on Backgrounder's owned `SupervisorJob` scope. macOS apps generally have foreground time, and a JVM process is fully yours; there's no OS-level "background runway" wrapping the call on either.

## What can go wrong

- **`Backgrounder.start()` not called yet** — `runNow` throws `IllegalStateException`. Calling order is `Backgrounder.create(...)` → `register(...)` (if you also have scheduled workers) → `start()` → `runNow(...)`.
- **Caller cancelled while the lambda holds a resource** — the lambda must observe cancellation; use `coroutineContext.ensureActive()` between non-suspending blocks, and put cleanup in `try`/`finally` rather than after `runNow`. This is normal Kotlin coroutine hygiene.
- **Thinking of `runNow` as a `schedule` shortcut** — it isn't. `schedule` outlives the caller and runs when the OS allows; `runNow` *is* the caller's work, just wrapped in an OS-granted background runway. If you backgrounded an in-flight `runNow` on iOS for 5 minutes, the work would still be cancelled when the grace window expired.
