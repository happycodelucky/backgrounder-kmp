# Architecture

Backgrounder is three layers, all in `/backgrounder`:

```text
┌────────────────────────────────────────────────────────────────────────┐
│  commonMain — public API                                               │
│    BackgroundWorker, WorkerContext, WorkRequest, WorkResult            │
│    BackgroundWorkerFactory — bulk task-id registration                 │
│    WorkerRegistry — task-id → factory map (the DI seam)                │
│    EphemeralRegistry — cold-launch sweep mirror                        │
│    Backgrounder — one per process (`shared`): register / start /      │
│                   schedule / cancel / cancelAll / scheduled /          │
│                   guarantees / runNow / shutdown                       │
└────────────────────────────────────────────────────────────────────────┘
        ▲                          ▲                            ▲
┌───────┴────────────┐ ┌───────────┴──────────┐ ┌───────────────┴────────┐
│  androidMain       │ │  iosMain             │ │  macosMain             │
│  WorkManager-      │ │  BGTaskBacked-       │ │  NSBackground-         │
│   Scheduler        │ │   Scheduler          │ │  ActivityBacked-       │
│  RegistryDispatch  │ │  + state store       │ │   Scheduler            │
│  Worker            │ │  + coroutine bridge  │ │                        │
│                    │ │  + foreground/       │ │                        │
│                    │ │    background feeds  │ │                        │
│  + WorkManager-    │ │                      │ │  + LibraryScope-       │
│    InstantRunner   │ │  + UIBackgroundTask- │ │    InstantRunner       │
│                    │ │    InstantRunner     │ │                        │
└────────────────────┘ └──────────────────────┘ └────────────────────────┘
```

ARM-only *native* targets — `iosArm64`, `iosSimulatorArm64`, Android `arm64-v8a`, `macosArm64` — plus the architecture-neutral `jvm` target (desktop / server). No Catalyst, no x86 native slices, no watchOS, no tvOS.

## Two surfaces, one entry point

`BackgroundTaskManager` exposes **two** parallel dispatch surfaces and a shared cancel:

- **Scheduling verbs** (`backgrounder.schedule(...)`, `backgrounder.cancelAll()`, `backgrounder.scheduled()`, `backgrounder.guarantees()`) — OS-backed scheduled work. Honors `WorkConstraints`, `BackoffPolicy`, retries. Workers come from the registry. See [Schedule a one-shot](../recipes/one-shot.md) / [Periodic](../recipes/periodic.md).
- **`runNow<R>(taskId, task)`** — instant dispatch. Suspends until the typed result is back. Bypasses constraints, backoff, retries, and the registry — the lambda *is* the work. Routed through the platform's real background primitive (`beginBackgroundTask` on iOS, `WorkManager` on Android, library scope on macOS and the JVM). See [Run now](../recipes/run-now.md).
- **`cancel(taskId)`** on `BackgroundTaskManager` is the unified cancel — it kills both scheduled and in-flight `runNow` for the given task id. `cancelAll()` covers only pending scheduled requests and does not touch in-flight `runNow` calls.

The two surfaces are independent code paths — a task id can flow through either or both. They share only the `BackgroundTaskManager` lifecycle (`start()` / `shutdown()`) and the cancel surface above.

## Why this shape

- **One public surface, four actuals.** Consumers in `commonMain` write against `BackgroundTaskManager` (scheduling verbs + `runNow`), `BackgroundWorker`, and `BackgroundWorkerFactory`. Each platform's `BackgroundTaskManager.Companion.create(...)` factory wires up its own internal scheduler and runner implementations, using plain constructor injection — no DI container required.
- **Workers are factory-built per invocation.** The library never instantiates a worker by reflection; the user registers a `() -> BackgroundWorker` factory at app launch and the library calls it each time the platform fires. This is the `@HiltWorker` model generalised for KMP — see [Worker context & DI](worker-context-and-di.md). (`runNow` is the exception — it uses a caller-supplied lambda directly instead of consulting the registry.)
- **State lives where the platform owns it.** Android persists requests in WorkManager's SQLite; iOS persists library-level retry/state in `NSUserDefaults` via `multiplatform-settings`. macOS holds active schedulers in-memory (the OS doesn't need persistence — `NSBackgroundActivityScheduler` is in-process); the JVM holds its coroutine jobs in-memory the same way (only the ephemeral mirror persists, via `java.util.prefs`). `runNow` is fully in-process — no persistence on any platform.
- **Guarantees are honest.** `BackgroundTaskManager.guarantees()` returns a per-platform truth table; UX should branch on it rather than assume parity. See [Guarantees](guarantees.md). `runNow` makes a different (weaker) set of guarantees — see [Run now](../recipes/run-now.md).
