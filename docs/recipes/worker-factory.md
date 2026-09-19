# Register many workers with a factory

`BackgroundWorkerFactory` is the bulk alternative to per-id `register(taskId) { worker }`. One factory owns a *set* of task ids and resolves the concrete `BackgroundWorker` lazily at dispatch time. Useful when an app module owns several related workers and you want to register them as a unit.

```kotlin
import com.happycodelucky.backgrounder.*

class SyncModuleWorkerFactory(private val graph: AppGraph) : BackgroundWorkerFactory {
    override val taskIds = setOf(
        SyncWorker.ID,
        UploadWorker.ID,
        ReconcileWorker.ID,
    )

    override fun create(taskId: String): BackgroundWorker? = when (taskId) {
        SyncWorker.ID      -> SyncWorker(repo = graph.repository)
        UploadWorker.ID    -> UploadWorker(api = graph.api, retryPolicy = graph.retryPolicy)
        ReconcileWorker.ID -> ReconcileWorker(repo = graph.repository, api = graph.api)
        else               -> null
    }
}

backgrounder.register(SyncModuleWorkerFactory(appGraph))
backgrounder.start()
```

The library invokes `create` afresh on every dispatch — workers are never cached.

## When to prefer a factory over per-id `register`

- An app module owns a related set of workers — register them in one call instead of N at the module's wiring site.
- Workers share the same DI graph — close over it once in the factory's constructor.
- You want worker construction to live next to the worker classes, not at the app's launch site.

For one or two workers, per-id `register(taskId) { worker }` is shorter and clearer. The two registration shapes coexist freely — mix them in the same `BackgroundTaskManager`.

## What can go wrong

- **`taskIds` and `create` drift apart.** If `create` returns `null` for an id that *is* in `taskIds`, the registry throws `WorkerRegistry.FactoryDeclinedException` — that's a programming error, not a fall-through. Keep the `when` exhaustive over `taskIds`.
- **An id is missing from `taskIds`.** The library registers OS handlers (Android WorkManager / iOS `BGTaskScheduler`) for every id in `taskIds` at `start()`. An id `create` *can* build but that isn't in `taskIds` will never get an OS handler — its scheduled work silently never fires. Keep the set complete.
- **Overlapping id sets.** Registering two factories that both claim the same task id, or a factory whose `taskIds` collides with an existing per-id `register`, throws `IllegalArgumentException` at registration. Resolution must be unambiguous.
- **iOS `Info.plist` requirement.** Every id in any factory's `taskIds` must also appear in `BGTaskSchedulerPermittedIdentifiers`. Same rule as per-id `register` — see [Schedule a one-shot](one-shot.md).

## Resolution order

When both per-id registrations and factories are in play:

1. Per-id `register(taskId, factory)` always wins for its id.
2. Factories are consulted in registration order — first one whose `taskIds` contains the id resolves it.

So you can register a factory that owns `{A, B, C}`, then override just `B` with a per-id `register(B) { ... }` — the per-id wins.
