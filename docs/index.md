---
title: Overview
hide:
  - navigation
---

# Backgrounder

**One Kotlin Multiplatform API for background work.** Schedule a job from `commonMain`; it runs on `WorkManager` on Android, on `BGTaskScheduler` on iOS, on `NSBackgroundActivityScheduler` on native macOS, and on library-owned coroutines on the JVM (desktop / server). No DI container required — workers are factory-built per dispatch from a closure you provide, so any DI graph you already have (Koin, Hilt, hand-wired) plugs in cleanly.

## Install

Add the library from `commonMain`; KMP resolves the right slice per target:

```kotlin
// shared/build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.happycodelucky.backgrounder:backgrounder:{{ version }}")
        }
    }
}
```

If you ship iOS, apply the Gradle plugin too. iOS only fires background tasks whose identifiers are listed in `Info.plist`; the plugin writes that list from your code so the two can't drift:

```kotlin
plugins {
    id("com.happycodelucky.backgrounder") version "{{ version }}"
}

backgrounder {
    iosInfoPlist = file("../iOSApp/App/Info.plist")
    iosBundleIdentifier = "dev.example.app"
}
```

Android-only apps, pure-Swift apps, platform floors, and the manifest entry Android needs are all in [Installation](installation.md).

## Configure

There is one `BackgroundTaskManager` per process, reachable anywhere as `BackgroundTaskManager.shared`. At launch, register every worker factory, then start:

=== "Android"

    ```kotlin
    class MyApp : Application(), Configuration.Provider {
        override fun onCreate() {
            super.onCreate()
            BackgroundTaskManager.shared.register(SyncWorker.ID) { SyncWorker(repo = appGraph.repo) }
            BackgroundTaskManager.shared.start()
        }

        override val workManagerConfiguration: Configuration get() =
            Configuration.Builder()
                .setWorkerFactory(BackgroundTaskManager.shared.androidWorkerFactory())
                .build()
    }
    ```

=== "iOS"

    ```swift
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        BackgroundTaskManager.shared.register(taskId: SyncWorker.companion.ID) {
            SyncWorker(repo: AppGraph.shared.repository)
        }
        BackgroundTaskManager.shared.start()
        return true
    }
    ```

The full per-platform sequences, including the Android manifest entry and the iOS `Info.plist` entries, are in [Getting started](getting-started.md).

## Run background work

```kotlin
// commonMain
class SyncWorker(private val repo: MyRepository) : BackgroundWorker {
    override suspend fun execute(context: WorkerContext): WorkResult {
        repo.sync()
        return WorkResult.Success
    }

    companion object {
        @BGTaskSchedulerPermittedIdentifier const val ID = "dev.example.app.sync"
    }
}

// Anywhere in your app
BackgroundTaskManager.shared.schedule(
    WorkRequest.OneTime(
        taskId = SyncWorker.ID,
        constraints = WorkConstraints(networkRequired = NetworkRequirement.Any),
        backoff = BackoffPolicy.exponential(initialDelay = 30.seconds, maxAttempts = 5),
    ),
)
```

The platform scheduler dispatches `SyncWorker` once the device has a network, retrying with backoff on `WorkResult.Retry`.

## What it does

- **Run now and survive backgrounding** — `BackgroundTaskManager.runNow<R>(taskId) { … }` runs a lambda in the background right now on the platform's real background primitive and suspends until the typed result is back. No constraints, no retries. See [Run now](recipes/run-now.md).
- **Schedule a one-time job** — `WorkRequest.OneTime` with constraints, input data, retry with backoff, and an `ephemeral` flag for work that must be re-scheduled after init. See [Schedule a one-shot](recipes/one-shot.md).
- **Periodic work** — `WorkRequest.Periodic`, native on Android and macOS, library-driven on iOS with coalescing so a task fires once per cycle. See [Schedule a periodic](recipes/periodic.md).
- **One worker contract** — `BackgroundWorker.execute(WorkerContext): WorkResult`, dependencies injected through the factory closure you register.
- **Inspect, cancel, monitor** — `scheduled()` with the reason each task is pending, `cancel()` / `cancelAll()`, and an `events()` flow.
- **Honest about platform differences** — `guarantees()` returns a per-platform truth table you can branch UX on.

## Why this exists

Android has `WorkManager` — rich, persistent, constraint-aware. Apple platforms have `BGTaskScheduler` and `NSBackgroundActivityScheduler` — different shape, different guarantees, opaque scheduling. The two worlds don't line up cleanly, and most KMP projects either:

1. Roll their own thin wrapper that pretends they do (and quietly drops on the floor whatever doesn't translate), or
2. Implement background work twice, once per platform, sharing nothing.

Backgrounder takes a third path: **a shared API that's honest about what each platform actually guarantees**, so consumers can write platform-aware UX (e.g. "Open the app daily so we can sync" on iOS only, where force-quit kills background tasks) without re-implementing scheduling itself.

## Next

- **[Getting started](getting-started.md)** — install, configure, run one job, on every platform.
- **[Installation](installation.md)** — version catalog, plugin, Android-only, SPM, platform floors.
- **[Concepts → Architecture](concepts/architecture.md)** — the three-layer design.
- **[Platforms → Force-quit caveat (iOS)](platforms/force-quit.md)** — the single most-often-misunderstood thing about iOS background work. Read this before shipping.

!!! warning "iOS limitation"
    When the user **force-quits the app** from the App Switcher, **all background tasks stop firing** until the user launches the app again. This is Apple's design. See [Force-quit on iOS](platforms/force-quit.md) for what to surface in your app's UI.
