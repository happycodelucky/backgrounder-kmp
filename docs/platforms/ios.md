# iOS launch sequence

!!! warning "Read the force-quit caveat first"
    iOS background tasks **stop firing entirely** when the user force-quits the app, until they manually launch it again. See [Force-quit caveat (iOS)](force-quit.md). This is the single most-often-misunderstood thing about iOS background work.

The iOS launch sequence is **two steps** — *register*, then *start* — run from `application(_:didFinishLaunchingWithOptions:)` before the launch method returns. `BackgroundTaskManager.shared` builds itself on first access; there is nothing to construct or hold.

```swift
@main
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions options:
            [UIApplication.LaunchOptionsKey: Any]?,
    ) -> Bool {
        // 1. BackgroundTaskManager.shared builds itself on first access, using the
        //    default tick identifier "<bundle id>.backgrounder-tick" for the
        //    BGAppRefreshTaskRequest that wakes periodic dispatch. To supply an
        //    event listener or your own tick identifier, call
        //    BackgroundTaskManager.companion.create(tickIdentifier:) before this line.
        let backgrounder = BackgroundTaskManager.shared

        // 2. Register every worker factory. Resolve dependencies from
        //    whatever DI graph your iOS app uses.
        backgrounder.register(taskId: SyncWorker.companion.ID) {
            SyncWorker(repo: AppGraph.shared.repository)
        }

        // 3. Start. Performs the iOS ephemeral sweep, registers
        //    BGTaskScheduler launch handlers (tick + per-id one-shots),
        //    starts the foreground dispatch loop, and resurrects active
        //    periodic state. Must run before this method returns.
        backgrounder.start()
        return true
    }
}
```

`BackgroundTaskManager.shared.start()` must be called **before the launch method returns** — `BGTaskScheduler.register` requires its handler to be registered before the app finishes launching, or iOS will refuse to dispatch tasks for that identifier in this process.

## Info.plist

You need **at least the tick identifier** plus one entry per `WorkRequest.OneTime` task id you schedule. Periodic task ids do **not** need their own entries — they're driven by the dispatcher through the tick.

The tick identifier defaults to `<bundle id>.backgrounder-tick` (`BackgroundTaskManager.companion.defaultTickIdentifier()` returns the exact string). Apps that call `BackgroundTaskManager.companion.create(tickIdentifier:)` use whatever they passed instead.

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>dev.example.app.backgrounder-tick</string>  <!-- mandatory: the default tick for bundle id dev.example.app -->
    <string>dev.example.app.upload</string>             <!-- one-shot WorkRequest.OneTime -->
</array>
```

You don't have to maintain this array by hand. Mark the tick and each one-shot id `@BGTaskSchedulerPermittedIdentifier const val` in the shared module and let the Gradle plugin rewrite the array from your code — see [Generate the iOS permitted identifiers](../recipes/ios-permitted-identifiers.md).

The library validates the tick identifier during `start()` (logs an error if missing — periodic dispatch is dead in the water without it) and warns about each registered factory id missing from the plist (you only need a per-id entry if you'll schedule that id as a `OneTime`; a periodic-only id doesn't need one).

## What runs where

The library has **two dispatch paths** on iOS, used in different parts of the app's lifecycle.

**One-shot tasks** (`WorkRequest.OneTime`) flow through per-task-id `BGTaskScheduler` registrations. iOS calls the registered launch handler when it decides to dispatch; the library bounces into a `SupervisorJob`-rooted `CoroutineScope` on `Dispatchers.Default` — never `GlobalScope`; the scope is owned by the iOS coroutine bridge with a defined cancellation lifecycle. `BGTask.expirationHandler` is wired to cancel the coroutine job; every `setTaskCompletedWithSuccess` call goes through a per-fire `CompletionGuard` so iOS's "completed twice" assertion can't trigger.

**Periodic tasks** (`WorkRequest.Periodic`) flow through an in-process dispatcher with two feeds:

- **Foreground feed** — observes `UIApplicationWillEnterForegroundNotification` / `UIApplicationDidEnterBackgroundNotification`. While the app is foregrounded, an in-process loop coroutine sleeps until the soonest periodic's next-run time, then drains everything that's currently due. This is what fires periodic work while the user is actively in the app — `BGAppRefreshTaskRequest` does **not** fire for foregrounded apps, so without this feed a periodic whose interval elapsed during a long user session would silently slip past its cycle.
- **Background feed** — registers the single library-owned tick identifier with `BGTaskScheduler` as a `BGAppRefreshTaskRequest`. When iOS decides to dispatch (e.g. the user has the app installed but hasn't opened it lately), the library walks the persisted scheduling table and runs every periodic that's currently due, then resubmits the next App Refresh request with `earliestBeginDate = soonestUpcomingNextRun()`.

The two feeds **coalesce by task id**: both consult the same `IOSStateStore` and acquire the same per-task `Mutex` before running a worker. The dispatcher advances `nextRunEpochMs` *before* invoking the worker, so a near-simultaneous race between the foreground loop and a background tick still results in exactly one worker run per cycle — the second arrival sees the advanced timestamp and skips.

Each foreground-initiated dispatch is wrapped in a `UIApplication.beginBackgroundTaskWithName` runway. If the user backgrounds the app mid-dispatch, iOS grants a continuation window (~30 seconds, sometimes a few minutes) before suspending the process, giving the worker time to finish. If the OS reclaims the runway before the worker completes, cancellation propagates through the dispatch scope and the worker is treated as `Retry` on the next tick.

## Coalescing missed intervals

If iOS doesn't wake the app for several intervals (foreground gap of hours, low power, App Refresh quota throttling), the worker fires **once** on the next dispatch — never N times back-to-back to "catch up." The library reschedules the next cycle from "now," not from the missed boundary, so cadence resyncs to the system's wake timing.

Concretely, a `Periodic` configured to run every 4 hours that iOS doesn't dispatch until 13 hours later fires once, not three or four times. If your worker needs to compensate for the gap (e.g. "fetch everything since the last sync"), put that logic inside the worker using your own persisted `lastSyncedAt` — the scheduler does not surface a missed-cycles count to the worker.

This contract holds across all three platforms (iOS, Android via WorkManager rebasing, macOS via `NSBackgroundActivityScheduler` one-wake-at-a-time).

## Foreground vs background execution windows

| Path | Window | Network constraint honored | Power constraint honored | Cancels in flight |
|---|---|---|---|---|
| Foreground feed (in-process loop) | No OS budget | Yes (library gate, ≤ 5 s) | No | Yes (when app backgrounds + runway expires) |
| `beginBackgroundTaskWithName` runway | ~30 s (sometimes a few minutes) | n/a | n/a | Yes (when runway expires) |
| Background feed (`BGAppRefreshTaskRequest`) | ~30 s | Yes (library gate, ≤ 5 s) | No | Yes (when iOS expires the BGTask) |
| One-shot `BGProcessingTaskRequest` | "several minutes" | Yes (OS hint + library gate, ≤ 5 s) | Yes (OS hint, advisory) | Yes (when iOS expires the BGTask) |
| One-shot `BGAppRefreshTaskRequest` (Expedited) | ~30 s | Yes (library gate, ≤ 5 s) | No | Yes |

**Network constraints are honoured everywhere via a library-managed pre-execution gate** (see [Recipes → Require a network connection](../recipes/network-required.md)). The gate waits up to `min(5 s, budget / 4)` for `Reachability` to satisfy `NetworkRequirement.Any` or `NetworkRequirement.Unmetered`; on timeout the worker is short-circuited to `WorkResult.Retry` and the scheduler reschedules per its `BackoffPolicy`. `Unmetered` is now honoured against `ReachabilityStatus.isDataMetered == false` (wifi/ethernet) — the legacy behaviour of downgrading to `Any` is gone.

**Power constraints (`requiresCharging`)** are still not honoured on iOS today — `BGProcessingTaskRequest.requiresExternalPower` is the only knob and Apple treats it as advisory; the foreground feed and `BGAppRefreshTaskRequest` ignore it entirely. Workers that need a charging precondition should check inside `execute()` and return `WorkResult.Retry` when uncharged.

## Concurrency

`BGTaskScheduler` may fire two distinct task identifiers concurrently. Per-task state operations (read attempt, write attempt, mark inactive) go through a `ConcurrentMap<String, Mutex>` — distinct task ids run independently; cross-task state is single-writer-per-key. The dispatcher uses the same per-task mutex map to coalesce foreground/background races.

## Testing

```
(lldb) e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"dev.example.app.background-tick"]
(lldb) e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateExpirationForTaskWithIdentifier:@"dev.example.app.background-tick"]
```

Use the **tick identifier** (not the per-task id) to simulate background dispatch of periodics. For one-shots, use the per-task id you scheduled.

Background tasks **do not fire automatically in the Simulator**. The LLDB simulate-launch hook is private (leading underscore) and only available on Simulator builds. On device, you wait for the system to dispatch — the foreground dispatch loop runs normally regardless.

