# Force-quit caveat (iOS)

!!! danger "Read before shipping iOS"
    When the user **force-quits the app from the App Switcher**, **all background tasks stop firing entirely** until the user manually launches the app again. Silent push notifications also stop. Background fetch stops. Library-emulated periodic schedules stop. The library cannot work around this — it's Apple's intentional design.

## Why

Apple treats force-quit as an explicit user signal: "I don't want this app running in the background." Across the BackgroundTasks framework, silent pushes, and `URLSession` background transfers, the behaviour is consistent — the system *will not dispatch* anything until the user launches the app themselves.

This is **not** the same as the OS killing your process for memory pressure or system reboot. Those, the library handles fine — see [Architecture](../concepts/architecture.md):

- **Process killed by OS** → state survives in `NSUserDefaults`; on next launch, the resurrection sweep in `backgrounder.start()` re-submits active periodic schedules.
- **Device reboot** → same.
- **User force-quits** → state survives, but **iOS refuses to dispatch handlers** until the user launches the app.

## What surfaces in your UX

`BackgroundTaskManager.guarantees().survivesForceQuit` is `false` on iOS. Branch on it:

```kotlin
if (!backgrounder.guarantees().survivesForceQuit) {
    // iOS-only educational nudge.
    showOnboardingTip(
        title = "Keep notifications fresh",
        body = "Open the app daily so we can sync. Force-quitting from the " +
               "App Switcher pauses background updates until you launch us again.",
    )
}
```

Concrete patterns:

- **Time-sensitive sync** (e.g. "your inbox refreshed at 9 AM"): set the user's expectation to "throughout the day" rather than wall-clock cadence.
- **One-shot uploads**: queue them, but warn "Uploads paused — tap to resume" if the user has force-quit and re-launched.
- **Long-running jobs**: don't promise completion in background. iOS has no equivalent of Android's `setForeground` for arbitrary work; the closest is `BGContinuedProcessingTaskRequest` (iOS 26+, must be submitted while the app is foregrounded), which is on the v2 roadmap.

## What to *not* do

- **Don't try to detect force-quit.** There's no API for it. Apps that "detect" it via heuristics are observing process termination, not user intent.
- **Don't bypass the limitation with `UIApplication.beginBackgroundTask`.** That's a 30-second extension of the *current* foreground session, not a scheduler.
- **Don't promise reliability.** Even without force-quit, `BGTaskScheduler.earliestBeginDate` is a hint; the system may defer indefinitely based on Low Power Mode, Doze-equivalent heuristics, and the user's recent app-usage patterns.

## What the library does

- Reports `survivesForceQuit = false` from `BackgroundTaskManager.guarantees()`.
- Resurrects active periodic schedules at next cold launch: force-quit drops the OS's pending-request set, so `backgrounder.start()` re-anchors each active periodic's next run and re-submits the tick request.
- Coalesces missed cycles rather than catching up — a periodic that's overdue by several intervals runs once on resurrection, not once per missed interval.

## On Android and macOS

Force-quit does **not** cause the iOS blacklisting problem:

- **Android.** `survivesForceQuit = true`. `WorkManager` survives force-stop (the user explicitly *force-stopping* an app via Settings does cancel work; that's a separate, more deliberate user action).
- **macOS.** `survivesForceQuit = false`. `NSBackgroundActivityScheduler` is in-process — schedules die with the process, the library does not persist them across launches, and nothing relaunches the app automatically. Unlike iOS, macOS does not blacklist the app from future background dispatch: re-schedule from your app's init path at the next launch and work resumes normally.

So the iOS blacklisting behaviour described above is iOS-specific. Read it before shipping; surface it in your UX; move on.
