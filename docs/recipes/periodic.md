# Schedule a periodic

Periodic jobs repeat indefinitely until cancelled. The Android floor is **15 minutes** and is validated at construction; iOS / macOS recommend the same for parity.

```kotlin
import kotlin.time.Duration.Companion.minutes

backgrounder.schedule(
    WorkRequest.Periodic(
        taskId = SyncWorker.ID,
        interval = 30.minutes,
        flexWindow = 5.minutes,                    // optional — work runs in the last 5 min of each window
        constraints = WorkConstraints(networkRequired = NetworkRequirement.Any),
    ),
)
```

## How `WorkResult` affects the schedule

| Worker returns        | Periodic semantics                                                     |
| --------------------- | ---------------------------------------------------------------------- |
| `Success`             | Reschedule at `now + interval`. Reset attempt counter to 0.            |
| `Failure(reason)`     | **Reschedule at `now + interval`** — a single failed run does not kill the schedule. Matches WorkManager. |
| `Retry`               | Reschedule at `now + backoff.delayFor(attempt)` (one-time deviation from cadence). The next regular run still follows. |

**Missed cycles fire once.** If the app was suspended or dead while N cycles came due, the task runs **once** on the next wake — never N times back-to-back. Android WorkManager, iOS App Refresh, and the in-process foreground feed all converge on "fire once when due", so the contract holds on every platform. Workers that need catch-up semantics should compute the gap from their own persisted state (e.g. a `lastProcessedAt` timestamp).

## iOS specifics

iOS has no native repeating-task primitive — periodic dispatch is **library-driven** through a two-feed dispatcher:

- **Foreground feed** (in-process loop) fires periodics while the user is in the app. iOS suppresses `BGAppRefreshTaskRequest` for foregrounded apps, so without this loop a periodic whose interval elapsed during a long user session would silently slip past.
- **Background feed** (single library-owned `BGAppRefreshTaskRequest` tick identifier) wakes the dispatcher when iOS decides the app should refresh — typically when the user has the app installed but hasn't opened it lately. On each wake, the dispatcher walks the persisted scheduling table and runs every periodic that's currently due.

The two feeds coalesce by task id through a per-task `Mutex`: even if the foreground loop and a background tick race for the same due task, only one cycle's worker runs.

`WorkConstraints` on `Periodic` are **not honored on iOS** — App Refresh ignores `requiresExternalPower` / `requiresNetworkConnectivity`, and the in-process loop has no constraint concept. If your periodic worker needs power/network gating, check inside `execute()` and return `WorkResult.Retry` when conditions aren't met.

The tick identifier must be in `Info.plist` — by default `<bundle id>.backgrounder-tick`, or whatever you passed to `BackgroundTaskManager.create(tickIdentifier:)` — see [iOS launch sequence](../platforms/ios.md). Periodic task ids do **not** need their own Info.plist entries (only one-shot ids do).

State is persisted (`tasks.<id>.kind = "periodic"`, `active = true`, `interval_ms`, `last_run_epoch_ms`, `next_run_epoch_ms`) so a force-quit + cold-launch path can resurrect the schedule on the next `backgrounder.start()` — the dispatcher resubmits a single tick request for the soonest active periodic.

The force-quit caveat still applies — see [Force-quit caveat (iOS)](../platforms/force-quit.md).

## macOS specifics

`NSBackgroundActivityScheduler` is natively repeating, so periodic is *not* emulated on macOS. The `repeats = true` flag plus `interval` and `tolerance` (mapped from `flexWindow`) are all the OS needs.
