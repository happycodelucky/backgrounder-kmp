---
title: Overview
hide:
  - navigation
---

# Backgrounder

**One Kotlin Multiplatform API for background work.** Schedule a job from `commonMain`; it runs on `WorkManager` on Android, on `BGTaskScheduler` on iOS, on `NSBackgroundActivityScheduler` on native macOS, and on library-owned coroutines on the JVM (desktop / server). No DI container required — workers are factory-built per dispatch from a closure you provide, so any DI graph you already have (Koin, Hilt, hand-wired) plugs in cleanly.

```kotlin
// commonMain
class SyncWorker(private val repo: MyRepository) : BackgroundWorker {
    override suspend fun execute(context: WorkerContext): WorkResult {
        repo.sync()
        return WorkResult.Success
    }

    companion object {
        const val ID = "dev.example.app.sync"
    }
}

// At app launch
val backgrounder = Backgrounder.create(application = this)        // Android
backgrounder.register(SyncWorker.ID) { SyncWorker(repo = appGraph.repo) }
backgrounder.start()

// Anywhere later
backgrounder.schedule(
    WorkRequest.OneTime(
        taskId = SyncWorker.ID,
        constraints = WorkConstraints(networkRequired = NetworkRequirement.Any),
        backoff = BackoffPolicy.exponential(initialDelay = 30.seconds, maxAttempts = 5),
    ),
)
```

## What it does

- **One scheduling API** across platforms — `Backgrounder.schedule()`, `cancel()`, `cancelAll()`, `scheduled()`.
- **One worker contract** — `BackgroundWorker.execute(WorkerContext): WorkResult`. Inject your dependencies through the factory closure you register at app launch.
- **Sealed `WorkRequest`** — `OneTime` and `Periodic`, both with input data, constraints, retry, and an `ephemeral` flag for the "ran-before-init" Android foot-gun.
- **Instant dispatch** — `Backgrounder.runNow<R>(taskId) { … }` runs a lambda in the background **right now** and suspends until the typed result is back. Bypasses constraints / retries / the registry; routed through the platform's real background primitive so the work survives an immediate app-background.
- **Honest about platform differences.** `Backgrounder.guarantees()` returns a per-platform truth table you can branch UX on.
- **No required DI dependency.** Backgrounder doesn't ship a DI module. The factory closure pattern works equally well with Koin, Hilt (Android), kotlin-inject, or hand-wired graphs.

## Why this exists

Android has `WorkManager` — rich, persistent, constraint-aware. Apple platforms have `BGTaskScheduler` and `NSBackgroundActivityScheduler` — different shape, different guarantees, opaque scheduling. The two worlds don't line up cleanly, and most KMP projects either:

1. Roll their own thin wrapper that pretends they do (and quietly drops on the floor whatever doesn't translate), or
2. Implement background work twice, once per platform, sharing nothing.

Backgrounder takes a third path: **a shared API that's honest about what each platform actually guarantees**, so consumers can write platform-aware UX (e.g. "Open the app daily so we can sync" on iOS only, where force-quit kills background tasks) without re-implementing scheduling itself.

## Get started

- **[Getting started](getting-started.md)** — zero-to-working in ~10 minutes.
- **[Installation](installation.md)** — Gradle DSL + version-catalog snippets, OS-floor table.
- **[Concepts → Architecture](concepts/architecture.md)** — the three-layer design.
- **[Platforms → Force-quit caveat (iOS)](platforms/force-quit.md)** — the single most-often-misunderstood thing about iOS background work. Read this before shipping.

!!! warning "iOS limitation"
    When the user **force-quits the app** from the App Switcher, **all background tasks stop firing** until the user launches the app again. This is Apple's design. See [Force-quit on iOS](platforms/force-quit.md) for what to surface in your app's UI.
