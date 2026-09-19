# Android launch sequence

The Android launch sequence is **two steps** — `register`, then `start` — plus one mandatory wiring: tell `WorkManager` to use Backgrounder's `WorkerFactory` via `Configuration.Provider`. Construction is automatic: the library registers an `androidx.startup` initializer that builds `Backgrounder.shared` before `Application.onCreate` runs.

```kotlin
import androidx.work.Configuration
import com.happycodelucky.backgrounder.Backgrounder
import com.happycodelucky.backgrounder.androidWorkerFactory
import com.happycodelucky.backgrounder.shared

class MyApp : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()

        // 1. Backgrounder.shared already exists: the library's androidx.startup
        //    initializer built it before onCreate ran. If your manifest removes
        //    the InitializationProvider entirely, call
        //    Backgrounder.configure(application = this) here first.

        // 2. Register every worker factory. The closure is yours — resolve
        //    dependencies however you like (Koin, Hilt, hand-wired).
        Backgrounder.shared.register(SyncWorker.ID) {
            SyncWorker(repo = appGraph.repository)
        }

        // 3. Start. Sweeps ephemeral work left over from the previous process,
        //    seals the registry, and flips the ready gate so workers enqueued
        //    before this point may now dispatch.
        Backgrounder.shared.start()
    }

    // Tell WorkManager to use Backgrounder's WorkerFactory. Required.
    override val workManagerConfiguration: Configuration get() =
        Configuration.Builder()
            .setWorkerFactory(Backgrounder.shared.androidWorkerFactory())
            .build()
}
```

## AndroidManifest

Disable WorkManager's default auto-init, which is mandatory whenever you implement `Configuration.Provider`. Remove only WorkManager's `meta-data` entry, not the provider: Backgrounder's own `BackgrounderInitializer` is registered on the same provider and is what populates `Backgrounder.shared`. If your app removes the provider altogether, call `Backgrounder.configure(application = this)` at the top of `onCreate` instead.

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

Without this, WorkManager's auto-init content provider runs before `Application.onCreate` and locks the `Configuration` to its defaults — your `workManagerConfiguration` override would never be consulted.

## Construction order

`Configuration.Provider.workManagerConfiguration` is invoked the *first time anyone* calls `WorkManager.getInstance(context)`, and it reads `Backgrounder.shared.androidWorkerFactory()`. The startup initializer builds `Backgrounder.shared` while content providers are created, which is before `Application.onCreate`, so the instance always exists by then. Construction itself never touches `WorkManager`; the ephemeral sweep that does runs inside `start()`.

If you call `Backgrounder.configure(application = this)` yourself (see below), do it at the top of `onCreate`, before anything can trigger `WorkManager.getInstance`.

## What runs where

- `RegistryDispatchWorker` (the single Worker class registered with WorkManager) is dispatched on WorkManager's executor — effectively `Dispatchers.Default`.
- The worker's `execute()` runs on a coroutine that inherits that dispatcher; switch with `withContext(Dispatchers.IO)` for blocking IO.
- Logs from inside the worker are tagged `Backgrounder/<taskId>` and the thread is named `Backgrounder/<taskId>` for the duration of `execute()`. This is the mitigation for the single-bridge-worker design — every log includes the task id even though the Worker class is the same for every task.

## Constraints are native

Android is the platform where `WorkConstraints` carries real OS weight. `networkRequired`, `requiresCharging`, and `requiresDeviceIdle` all map straight onto `androidx.work.Constraints` and are enforced by `JobScheduler` — WorkManager will not dispatch the worker until every constraint holds, holding it indefinitely if needed.

`requiresDeviceIdle = true` in particular has no equivalent on the other targets: it maps to `Constraints.Builder.setRequiresDeviceIdle(true)`, so the work runs only when the device enters idle / a Doze maintenance window. A task waiting on it reports `PendingPredicate.RequiresDeviceIdle` from `backgrounder.scheduled()`. On iOS / macOS / JVM the same flag is advisory and ignored — see [Opportunistic dispatch](../concepts/opportunistic-dispatch.md) and [Require the device to be idle](../recipes/network-required.md#require-the-device-to-be-idle).

## Multi-process apps

The `androidx.startup` provider runs only in the main process, so in a `:remote` process `Backgrounder.shared` is not populated automatically. Call `Backgrounder.configure(application = this)` at the top of `Application.onCreate`, which runs in *every* process. The call is idempotent with the initializer: when an instance already exists and you pass no listener or `WorkManager`, it returns that instance. Each process holds its own `Backgrounder`, but they share the same `WorkManager` database, so scheduled work is consistent across processes.
