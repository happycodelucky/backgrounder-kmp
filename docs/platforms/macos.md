# macOS launch sequence

```swift
@main
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // BackgroundTaskManager.shared builds itself on first access. Call
        // BackgroundTaskManager.companion.create(eventListener:) before this line only
        // if you want an event listener.

        // 1. Register every worker factory.
        BackgroundTaskManager.shared.register(taskId: SyncWorker.companion.ID) {
            SyncWorker(repo: AppGraph.shared.repository)
        }

        // 2. Start — sweeps ephemeral state and seals the registry.
        BackgroundTaskManager.shared.start()
    }

    func applicationWillTerminate(_ notification: Notification) {
        // Cancel the scheduler's coroutine scope cleanly.
        BackgroundTaskManager.shared.shutdown()
    }
}
```

`NSBackgroundActivityScheduler` owns scheduling lifetime entirely, so unlike iOS there's no per-cold-launch handler-registration ceremony. `start()` does just two things on macOS:

1. Sweep ephemeral state.
2. Seal the `WorkerRegistry` so further `register()` calls throw.

## What runs where

- `NSBackgroundActivityScheduler.scheduleWithBlock` invokes a closure on a system queue.
- The library bounces into a `SupervisorJob` + `Dispatchers.Default` scope (the same pattern as iOS), runs the worker, maps `WorkResult` to `NSBackgroundActivityResultFinished` (Success / Failure) or `NSBackgroundActivityResultDeferred` (Retry).
- `cancel(taskId)` calls `invalidate()` on the live scheduler — interrupts the running block. `cancelsInFlight = true`.

## Network constraints — library-managed gate

`NSBackgroundActivityScheduler` has no constraint concept of its own — no `requiresNetworkConnectivity`, no `requiresExternalPower`. Without the library filling the gap, `WorkConstraints.networkRequired` would be silently ignored on macOS.

The library inserts a pre-execution **reachability gate** that waits up to `min(5 s, ctx.capabilities.maxExecutionTime / 4)` (collapses to ≈5 s under the conservative 5-minute macOS budget) for the requirement to become true. On timeout the worker is short-circuited to `WorkResult.Retry`; `handleOneShotRetry` reschedules a fresh activity with `interval = backoff.delayFor(attempt)`. See [Recipes → Require a network connection](../recipes/network-required.md).

`Unmetered` is honoured against `ReachabilityStatus.isDataMetered == false`. Power and idle constraints (`requiresCharging`, `requiresDeviceIdle`) are not enforced on macOS — `NSBackgroundActivityScheduler` exposes neither, though it already biases toward idle moments via `qualityOfService = .background`. Workers needing a charging or idle precondition should check inside `execute()` and return `Retry`.

## Periodic is native

macOS doesn't need the iOS periodic-emulation state machine. `NSBackgroundActivityScheduler` has `repeats = true`, `interval`, and `tolerance` (mapped from `WorkRequest.Periodic.flexWindow`). The library hands the OS a single repeating activity per task id and lets it dispatch.

## Force-quit on macOS

`survivesForceQuit = false` — `NSBackgroundActivityScheduler` is in-process; all registered activities die with the process, and the library does not persist schedules across launches on macOS. Unlike iOS, macOS does **not** blacklist the app from future background dispatch: anything you schedule after relaunch dispatches normally. Re-schedule from your app's init path after `backgrounder.start()` at each launch.

## Shutdown

`BackgroundTaskManager.shared.shutdown()` cancels the scheduler's `SupervisorJob`-rooted scope. Call from `applicationWillTerminate` to tear down cleanly. Without it, in-flight workers continue until the OS reclaims the process — for a foreground app being explicitly quit, that's a few extra seconds of work that never matters; for a long-lived agent it can leave file handles open. Always pair with `applicationWillTerminate`.
