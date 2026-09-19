# JVM launch sequence

The JVM target serves desktop apps and server-side processes. There is no OS background scheduler to delegate to, so Backgrounder schedules with library-owned coroutines — the same in-process model as macOS, minus Foundation.

```kotlin
fun main() {
    // BackgroundTaskManager.shared builds itself on first access. Call
    // BackgroundTaskManager.create(eventListener = …) before this line only if you
    // want an event listener.

    // 1. Register every worker factory.
    BackgroundTaskManager.shared.register(SyncWorker.ID) {
        SyncWorker(repo = appGraph.repository)
    }

    // 2. Start — sweeps ephemeral state and seals the registry.
    BackgroundTaskManager.shared.start()

    // 3. Tear down on exit (or from your UI framework's teardown hook).
    Runtime.getRuntime().addShutdownHook(
        Thread { BackgroundTaskManager.shared.shutdown() },
    )
}
```

`start()` does just two things on the JVM:

1. Sweep ephemeral state.
2. Seal the `WorkerRegistry` so further `register()` calls throw.

## What runs where

- Each scheduled request becomes one coroutine `Job` on a `SupervisorJob` + `Dispatchers.Default` scope owned by the scheduler. One-shots `delay(initialDelay)` then run; periodics loop `delay(interval)` → run.
- Retry backoff is library-emulated, exactly like iOS and macOS: a one-shot returning `WorkResult.Retry` re-runs after `backoff.delayFor(attempt)`, bounded by `maxAttempts`.
- `cancel(taskId)` cancels the live `Job` — interrupts a running worker. `cancelsInFlight = true`.
- Because the library *is* the scheduler here, `scheduled()` reports exact `nextRunHint` values and `WorkStarted.expectedAt` is always populated — the JVM is the most introspectable platform.

## Network constraints — library-managed gate

Same story as macOS: there is no OS constraint concept, so the library inserts a pre-execution **reachability gate** (powered by [reachable](https://github.com/happycodelucky/reachable)) that waits a bounded window for `WorkConstraints.networkRequired` to be satisfied. On timeout the worker is short-circuited to `WorkResult.Retry` and rescheduled per the request's `BackoffPolicy`. See [Recipes → Require a network connection](../recipes/network-required.md).

`Unmetered` is honoured against `ReachabilityStatus.isDataMetered == false`. Power and idle constraints (`requiresCharging`, `requiresDeviceIdle`) are not enforced — there is no portable JVM power or device-idle API; workers that need either precondition should check inside `execute()` and return `Retry`.

## Periodic is a coroutine loop

No emulation state machine, no OS coalescing. The scheduler fires once per `interval`, first fire after one full interval — matching macOS and Android cadence. `flexWindow` is ignored (there is no OS to coalesce with), and missed cycles coalesce into a single fire on resume, per the cross-platform contract ([missed cycles](../recipes/periodic.md)).

## Nothing survives the process

`survivesProcessDeath / survivesReboot / survivesForceQuit = false` — schedules live in your process and die with it, and nothing relaunches a JVM. Persistence is limited to the ephemeral-task mirror (stored via `java.util.prefs.Preferences` so the cold-start sweep works across launches). Re-schedule from your app's init path after `backgrounder.start()` at each launch — the same pattern as [macOS](macos.md).

## Shutdown

`backgrounder.shutdown()` cancels the scheduler's `SupervisorJob`-rooted scope and the `runNow` runner's. Call it from a JVM shutdown hook (long-running services) or your UI framework's teardown (desktop apps). Without it, in-flight workers run until the JVM exits — harmless for a process that's quitting anyway, but a long-lived embedder (e.g. hosting Backgrounder inside a larger server) should always call `shutdown()`, which also releases the `BackgroundTaskManager.shared` slot so a fresh instance can be built.
