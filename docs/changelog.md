# Changelog

## Unreleased

### Docs: API reference is linked and complete

- The Dokka API reference now has an **API reference** tab in the site navigation and a link from the Overview page. It previously built to an empty "All modules" page (the root project's Gradle coordinates collided with `:backgrounder`'s, so the aggregate resolved to itself); it now covers `backgrounder` and `background-monitor`.

### `Backgrounder` class renamed to `BackgroundTaskManager`

- The entry-point class is now `BackgroundTaskManager`; `Backgrounder` remains the library, Maven group, framework module, and Gradle plugin name. Two reasons: the class name now says what the object is, and the old name collided with the Apple framework module, which made SKIE expose it to Swift as `Backgrounder_`. Swift now reads `BackgroundTaskManager.shared` after `import Backgrounder`. Library-branded siblings (`BackgrounderEventListener`, `BackgrounderInitializer`, `BackgrounderWorkerFactory`) keep their names.

### One `BackgroundTaskManager` per process: `BackgroundTaskManager.shared`

- There is now exactly one live `BackgroundTaskManager` per process, reachable anywhere as `BackgroundTaskManager.shared` (Kotlin and Swift). Nothing needs to be injected or passed around; `single { BackgroundTaskManager.shared }` if you want it in a DI graph. A second live instance throws; `shutdown()` releases the slot.
- **Android**: the library's manifest registers `BackgrounderInitializer` with `androidx.startup`, so `shared` exists before `Application.onCreate`. `BackgroundTaskManager.create(application)` is renamed `BackgroundTaskManager.configure(application)` and is only needed by apps that removed the `InitializationProvider` or run in extra processes; it is idempotent with the initializer. `workManagerConfiguration` reads `BackgroundTaskManager.shared.androidWorkerFactory()`.
- **iOS / macOS / JVM**: `shared` builds itself on first access. `create(...)` remains for apps that want an event listener or, on iOS, their own tick identifier, and installs its result as `shared`.
- **iOS default tick identifier**: `<bundle id>.backgrounder-tick`, returned by `BackgroundTaskManager.companion.defaultTickIdentifier()`. The Gradle plugin adds it to the plist when `backgrounder.iosBundleIdentifier` is set.
- **Ephemeral sweep timing**: on every platform the leftover ids are snapshotted when the instance is built and cancelled at `start()`. On Android this moves the sweep out of construction (which may now run before `onCreate`) and means an ephemeral request scheduled between construction and `start()` is never mistaken for a leftover.
- Swift gets `BackgroundTaskManager.shared` through a wrapper bundled into the framework by SKIE; the raw bridge function is `BackgroundTaskManager.companion.sharedInstance()`.

### Gradle plugin: generated `BGTaskSchedulerPermittedIdentifiers`

- New `@BGTaskSchedulerPermittedIdentifier` annotation in `:backgrounder`. Put it on the `const val String` ids that belong in the iOS `BGTaskSchedulerPermittedIdentifiers` array — the tick identifier and any id you may schedule as a `OneTime` — wherever they live: top level, `object`, or `companion object`. iOS-only; periodic and `runNow` ids don't need it, and annotating them is harmless.
- New `com.happycodelucky.backgrounder` Gradle plugin (`:backgrounder-gradle-plugin`, published to Maven Central). `collectBackgroundTaskIds` scans the module's compiled JVM classes for annotated constants and writes a manifest; `updateBackgrounderInfoPlist` rewrites the `BGTaskSchedulerPermittedIdentifiers` array in the configured `Info.plist` from it, touching nothing else in the file. Duplicate, blank, or non-`const` ids fail the build; non-reverse-DNS ids warn. See [Generate the iOS permitted identifiers](recipes/ios-permitted-identifiers.md).
- The plugin has no dependency on the Kotlin Gradle plugin or on `:backgrounder`; it finds the compile task by name and matches the annotation by descriptor.

### Task ids are plain strings

- `TaskId` is gone. Every API that took a `TaskId` now takes a `String`: `register`, `WorkRequest.OneTime` / `Periodic`, `runNow`, `cancel`, `WorkerContext.taskId`, `ScheduledTask.taskId`, `BackgroundWorkerFactory.taskIds`, `FactoryDescriptor`, and `MonitorEvent`. Swift already saw these as `String` (Kotlin/Native exports a value class as its underlying type), so the Kotlin and Swift surfaces now match.
- The reverse-DNS shape is no longer enforced. Ids are free-form; reverse-DNS is the documented convention. See [Task ids](concepts/task-ids.md).
- The remaining rules, non-blank with no surrounding whitespace and no control characters, is checked at every public entry point instead of in a constructor, so Swift callers are validated too. `BackgroundTaskManager.create(tickIdentifier:)` now validates the tick identifier for the first time and is annotated `@Throws(IllegalArgumentException::class)`; `runNow` gains `IllegalArgumentException` in its `@Throws` list.
- Persisted state is unaffected: `TaskId` serialized as its underlying string, so stored schedules keep loading.

### Instant dispatch — `BackgroundTaskManager.runNow`

- New `suspend fun <R> BackgroundTaskManager.runNow(taskId, task): R` for "run this lambda in the background **right now** and let me `await` the typed result." Complements scheduled work — bypasses `WorkConstraints`, `BackoffPolicy`, retries, and the `BackgroundWorker` / `register` path entirely; the lambda *is* the work. See [Run now](recipes/run-now.md).
- Routed through the platform's real background primitive so the work survives if the caller backgrounds mid-call:
    - **Android**: `WorkManager` (a synthetic one-time request keyed `${taskId}::runNow`).
    - **iOS**: `UIApplication.beginBackgroundTask(withName:expirationHandler:)` — *not* `BGTaskScheduler`. `BGTaskScheduler` requires `Info.plist` permitted-identifiers and is for deferred work; `beginBackgroundTask` grants ~30s of grace if the app backgrounds during the call, with no `Info.plist` requirement. The task id is purely an in-process pre-emption key on iOS — never sent to the OS scheduler.
    - **macOS**: library-owned `SupervisorJob` scope (macOS apps generally have foreground time; `NSBackgroundActivityScheduler` is interval-shaped and a poor fit for one-shot dispatch).
- **Pre-emption is the contract.** `runNow(taskId, …)` cancels any in-flight `runNow`, any pending scheduled request, and any in-flight scheduled worker for the same task id **before** submitting its own request. Concurrent `runNow` calls with the same task id are "last call wins" — two typed results to one caller would be ambiguous.
- **Unified `BackgroundTaskManager.cancel(taskId)`** cancels everything for a task id — scheduled requests *and* in-flight `runNow`. `BackgroundTaskManager.cancelAll()` covers only pending scheduled requests and does not touch in-flight `runNow` calls.
- Structured concurrency throughout: caller cancellation cancels the OS request, the lambda observes `CancellationException`, and the caller's `await` rethrows. Lambda exceptions propagate to the caller via `@Throws`.

### iOS periodic dispatch

- `WorkRequest.Periodic` is now driven by a coalescing dispatcher with two feeds — an in-process loop while the app is foregrounded, and a single library-owned `BGAppRefreshTaskRequest` while it is not. iOS suppresses `BGAppRefreshTaskRequest` for foregrounded apps, so the in-process loop is what fires periodics at the right moment during user sessions; without it a periodic whose interval elapsed during a long session would silently slip past until the user backgrounded the app.
- Foreground-initiated dispatch is wrapped in `UIApplication.beginBackgroundTaskWithName` runway so work that gets backgrounded mid-execution gets the OS-granted continuation window before being treated as `Retry`.
- Coalescing-by-task-id is an explicit cross-platform contract (documented on `WorkRequest.Periodic` and in [iOS launch sequence](platforms/ios.md)). If iOS doesn't dispatch for several intervals, the worker fires once on the next wake — never N times back-to-back to "catch up." Workers that need catch-up logic compute it from their own persisted state (e.g. `lastSyncedAt`).
- `BackgroundTaskManager.create(tickIdentifier:)` takes a required tick identifier on iOS. Pick a string in your app's reverse-DNS namespace (e.g. `"<your.bundle.id>.background-tick"`) and add it to `BGTaskSchedulerPermittedIdentifiers` in `Info.plist`. Periodic task ids do not need their own `Info.plist` entries — the tick handles them. One-shot task ids (`WorkRequest.OneTime`) still register per-id and still need their own entries.
- `WorkConstraints` on `WorkRequest.Periodic` are not honored on iOS — App Refresh has no constraint fields; the in-process loop has no constraint concept. Workers that need power/network gating should check at the start of `execute()` and return `WorkResult.Retry`. `WorkConstraints` are honored for `WorkRequest.OneTime` on iOS, and on Android / macOS for both kinds.

### v1 surface

First public artifact in preparation. The v1 surface is feature-complete:

- Constructed-instance `BackgroundTaskManager` entry point. Three-step launch: `BackgroundTaskManager.create(...)` → `register(...)` → `start()`. Hold one instance per app for the lifetime of the process.
- **Two registration shapes.** Per-id: `register(taskId) { factory }` for a single task id. Bulk: `register(factory: BackgroundWorkerFactory)` for one factory object that owns many task ids. Overlapping id sets are rejected at registration time; resolution order is per-id first, then factories in registration order.
- **No DI container required.** Factory closures resolve dependencies from whatever DI graph the consumer already uses (Koin, Hilt, kotlin-inject, hand-wired); the library itself ships zero DI dependency.
- Scheduling verbs promoted directly onto `BackgroundTaskManager`: `schedule` / `cancel` / `cancelAll` / `scheduled()` / `guarantees()`. Pass the `BackgroundTaskManager` instance wherever scheduling is needed — no separate `Scheduler` handle.
- Sealed `WorkRequest`: `OneTime` and `Periodic`, both with `ephemeral` flag.
- `BackoffPolicy` (Linear / Exponential) with `maxAttempts`.
- `ExecutionHint`: `Standard` and `Expedited(QuotaPolicy)`.
- `WorkInput` typed key/value bag, capped at 10 240 bytes.
- Cold-launch ephemeral sweep with platform-appropriate timing + per-instance Android ready-gate backstop.
- iOS periodic emulation via library-internal state machine, with force-quit resurrection.
- macOS native periodic via `NSBackgroundActivityScheduler`.
- Per-platform `SchedulerGuarantees` for honest UX branching.
- Android: hand-rolled `BackgrounderWorkerFactory` that consumers install via `Configuration.Provider.workManagerConfiguration`. Composes with Hilt's `HiltWorkerFactory` via `DelegatingWorkerFactory`.
- MkDocs Material documentation site with Dokka API reference.

## v2 roadmap (not yet)

- Reactive `Scheduler.observe()` Flow (cross-platform).
- `ExecutionHint.LongRunning` for Android `setForeground` / foreground-service work.
- Android-only constraints (storage-not-low, device-idle, content URI triggers) in a `WorkConstraints.Android` extension.
- Published `:testing` artifact with stable, public `FakeScheduler`.

The latest version of this changelog lives at the [GitHub Releases page](https://github.com/happycodelucky/backgrounder-kmp/releases) once published.
