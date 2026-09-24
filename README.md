<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/banner-dark.svg">
    <img src="docs/assets/banner-light.svg" alt="Backgrounder — one Kotlin Multiplatform API for background work. A macOS window in focus, with background work receding behind it.">
  </picture>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/iOS-18%2B-blue.svg?style=for-the-badge&logo=apple" alt="iOS 18+">
  <img src="https://img.shields.io/badge/macOS-15%2B-blue.svg?style=for-the-badge&logo=apple" alt="macOS 15+">
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white" alt="Android 11+">
  <img src="https://img.shields.io/badge/JVM-21%2B-orange.svg?style=for-the-badge&logo=openjdk&logoColor=white" alt="JVM 21+">
  <img src="https://img.shields.io/badge/Kotlin-2.4-7F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin 2.4">
  <a href="https://github.com/happycodelucky/backgrounder-kmp/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/happycodelucky/backgrounder-kmp/ci.yml?style=for-the-badge&label=ci" alt="CI"></a>
  <a href="https://github.com/happycodelucky/backgrounder-kmp/actions/workflows/docs.yml"><img src="https://img.shields.io/github/actions/workflow/status/happycodelucky/backgrounder-kmp/docs.yml?style=for-the-badge&label=docs" alt="Docs"></a>
  <a href="https://github.com/happycodelucky/backgrounder-kmp/releases/latest"><img src="https://img.shields.io/github/v/release/happycodelucky/backgrounder-kmp?style=for-the-badge" alt="Release"></a>
</p>

---

Backgrounder wraps each platform's background-scheduling primitive behind one API you call from `commonMain`:

- **Android**: Jetpack `WorkManager` (one-shot + periodic, with constraints, retry, expedited).
- **iOS 18+**: `BGTaskScheduler` (one-shot + library-emulated periodic; force-quit caveat documented).
- **macOS 15+**: Foundation's `NSBackgroundActivityScheduler` (one-shot + native periodic).
- **JVM 21+** (desktop / server): library-owned coroutines (one-shot + periodic; in-process, nothing survives the process).

Full documentation: **[happycodelucky.github.io/backgrounder-kmp](https://happycodelucky.github.io/backgrounder-kmp/)**.

---

## Installation

### 1. The library

Backgrounder publishes to Maven Central. From a Kotlin Multiplatform project, depend on it from `commonMain` and KMP resolves the right slice per target (Android AAR, `jvm` JAR, `iosArm64`, `iosSimulatorArm64`, `macosArm64`):

```kotlin
// shared/build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.happycodelucky.backgrounder:backgrounder:0.9.0")
        }
    }
}
```

Android-only apps depend on the Android artifact directly:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.happycodelucky.backgrounder:backgrounder-android:0.9.0")
}
```

Pure-Swift apps add this repository as a Swift Package Manager dependency pinned to a release tag; the tagged `Package.swift` hands SPM a prebuilt, SKIE-enhanced `Backgrounder.xcframework`. See [Installation](https://happycodelucky.github.io/backgrounder-kmp/installation/) for platform floors and the SPM details.

### 2. The Gradle plugin (iOS apps)

iOS only fires a background task whose identifier is listed in the app's `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`. Keeping that list in sync with code by hand is the classic way to ship a task that silently never runs. The Backgrounder Gradle plugin writes the list from your code instead:

```kotlin
// shared/build.gradle.kts
plugins {
    kotlin("multiplatform")
    id("com.happycodelucky.backgrounder") version "0.9.0"
}

backgrounder {
    iosInfoPlist = file("../iOSApp/App/Info.plist")
    iosBundleIdentifier = "dev.example.app"   // adds the default tick identifier for you
}
```

Mark each task id that iOS must know about — every id you may schedule as a one-shot — and run `./gradlew updateBackgrounderInfoPlist` (from an Xcode run-script phase, a pre-commit hook, or by hand):

```kotlin
class UploadWorker(...) : BackgroundWorker {
    companion object {
        @BGTaskSchedulerPermittedIdentifier const val ID = "dev.example.app.upload"
    }
}
```

Periodic work and `runNow` never touch `BGTaskScheduler` and need no entry. If you'd rather maintain the plist yourself, the required entries are the tick identifier (`<bundle id>.backgrounder-tick` by default) plus one per one-shot id; the library logs an error at `start()` when the tick is missing and a warning for each registered id that is. See [Generate the iOS permitted identifiers](https://happycodelucky.github.io/backgrounder-kmp/recipes/ios-permitted-identifiers/).

### 3. Android: `Configuration.Provider` and the manifest

Backgrounder builds your workers through its own `WorkerFactory`. WorkManager only accepts a custom factory from an `Application` that implements `Configuration.Provider`, so declare it there:

```kotlin
// Implement Configuration.Provider so WorkManager asks *you* for its setup
// instead of initialising itself with defaults.
class MyApp : Application(), Configuration.Provider {

    // WorkManager calls this once, the first time anything touches
    // WorkManager.getInstance(). It must return Backgrounder's factory, or
    // WorkManager can't construct your BackgroundWorkers.
    override val workManagerConfiguration: Configuration get() =
        Configuration.Builder()
            // BackgroundTaskManager.shared already exists here: the library's
            // androidx.startup initializer built it before onCreate ran.
            .setWorkerFactory(BackgroundTaskManager.shared.androidWorkerFactory())
            .build()
}
```

Implementing `Configuration.Provider` also requires disabling WorkManager's automatic initialisation in `AndroidManifest.xml`, otherwise it initialises with defaults before `onCreate` and never asks you. Remove only WorkManager's entry: Backgrounder's own startup initializer, the thing that creates `BackgroundTaskManager.shared`, rides on the same provider:

```xml
<!-- Merge into androidx.startup's provider rather than replacing it, so
     initializers registered by libraries (including Backgrounder's) survive. -->
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    tools:node="merge">
    <!-- Drop only WorkManager's initializer. -->
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

If your app removes the provider entirely, call `BackgroundTaskManager.configure(application = this)` at the top of `onCreate` instead.

---

## What you can do

Three kinds of background work, one `BackgroundTaskManager`:

**Run now and survive backgrounding.** The user taps Save and switches apps. `runNow` runs your lambda immediately on the platform's real background primitive (`UIApplication.beginBackgroundTask` on iOS, `WorkManager` on Android, a library scope on macOS and the JVM), so it finishes even if the app is backgrounded mid-call, and suspends until the typed result is back. No constraints, no retries: the lambda *is* the work.

```kotlin
val saved: SavedDocument = BackgroundTaskManager.shared.runNow(SaveTask.ID) {
    repo.save(draft)
}
```

**Schedule a one-time job.** Work that should happen once, when conditions allow, and outlive the current process: an upload that waits for a network, a cleanup that waits for charging. Persisted by the platform, retried with backoff on `WorkResult.Retry`, replaceable or deduplicated by task id.

```kotlin
BackgroundTaskManager.shared.schedule(
    WorkRequest.OneTime(
        taskId = UploadWorker.ID,
        constraints = WorkConstraints(networkRequired = NetworkRequirement.Any),
        backoff = BackoffPolicy.exponential(initialDelay = 30.seconds, maxAttempts = 5),
    ),
)
```

**Periodic work.** A sync every few hours. Runs on `WorkManager`'s periodic requests on Android and `NSBackgroundActivityScheduler` on macOS. On iOS the library drives it through one `BGAppRefreshTaskRequest` plus an in-process loop while the app is foregrounded, coalescing so a task fires once per cycle, never in a catch-up burst.

```kotlin
BackgroundTaskManager.shared.schedule(
    WorkRequest.Periodic(taskId = SyncWorker.ID, interval = 6.hours),
)
```

Around those: `cancel(taskId)` and `cancelAll()`, `scheduled()` to inspect what's pending and why, an `events()` flow for monitoring, and `guarantees()` for the per-platform truth table below.

---

## Initialize and use

Define a worker in `commonMain`. Workers are built by a factory you register, never by reflection, so they take dependencies through the constructor:

```kotlin
class SyncWorker(private val repo: MyRepository) : BackgroundWorker {
    override suspend fun execute(context: WorkerContext): WorkResult =
        try {
            repo.sync()
            WorkResult.Success
        } catch (t: Throwable) {
            WorkResult.Retry   // retried per the request's BackoffPolicy
        }

    companion object {
        const val ID = "dev.example.app.sync"
    }
}
```

There is one `BackgroundTaskManager` per process, `BackgroundTaskManager.shared`. At launch, register every worker factory, then start. Nothing is constructed or passed around.

**Android** — `shared` already exists when `onCreate` runs (built by the startup initializer):

```kotlin
// Configuration.Provider is required: see "Android: Configuration.Provider and
// the manifest" under Installation for what it does and the manifest entry.
class MyApp : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()

        // Register a factory for every worker. The closure runs on each
        // dispatch and builds a fresh worker, resolving dependencies from
        // whatever DI graph you use (Koin, Hilt, hand-wired).
        BackgroundTaskManager.shared.register(SyncWorker.ID) { SyncWorker(repo = appGraph.repo) }

        // Start: sweeps ephemeral work left over from the previous process,
        // seals the registry (no more register calls), and lets any work
        // WorkManager already has queued dispatch to your workers.
        BackgroundTaskManager.shared.start()
    }

    // Hand WorkManager the factory that knows how to build your workers.
    override val workManagerConfiguration: Configuration get() =
        Configuration.Builder()
            .setWorkerFactory(BackgroundTaskManager.shared.androidWorkerFactory())
            .build()
}
```

**iOS** — `shared` builds itself on first access. Register and start before the launch method returns:

```swift
func application(_ application: UIApplication,
                 didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    // First access builds the instance, using "<bundle id>.backgrounder-tick"
    // as the BGAppRefreshTaskRequest identifier that wakes periodic work. To
    // pass your own tick identifier or an event listener, call
    // BackgroundTaskManager.companion.create(tickIdentifier:) before this line.
    let manager = BackgroundTaskManager.shared

    // Register a factory for every worker; the closure builds a fresh worker
    // per dispatch from your iOS app's DI graph.
    manager.register(taskId: SyncWorker.companion.ID) {
        SyncWorker(repo: AppGraph.shared.repository)
    }

    // Start: sweeps ephemeral work, registers the BGTaskScheduler launch
    // handlers (the tick plus one per one-shot id), starts the in-process
    // loop that fires periodics while the app is foregrounded, and resumes
    // any periodic schedule persisted by a previous launch. iOS requires the
    // handlers to be registered before this method returns.
    manager.start()
    return true
}
```

**macOS and JVM** — the same two calls from `applicationDidFinishLaunching` or `main()`, plus `BackgroundTaskManager.shared.shutdown()` on exit to cancel the library-owned coroutine scopes.

The factory closure is where DI happens: resolve from Koin, Hilt, kotlin-inject, or hand-wired singletons. To register a whole module's workers at once, pass a `BackgroundWorkerFactory`. Then schedule from anywhere in your code as shown above.

Full launch sequences, including the iOS force-quit caveat and how to simulate background dispatch in the simulator, live in the docs: [Android](https://happycodelucky.github.io/backgrounder-kmp/platforms/android/), [iOS](https://happycodelucky.github.io/backgrounder-kmp/platforms/ios/), [macOS](https://happycodelucky.github.io/backgrounder-kmp/platforms/macos/), [JVM](https://happycodelucky.github.io/backgrounder-kmp/platforms/jvm/).

---

## What each platform actually guarantees

Read at runtime via `BackgroundTaskManager.shared.guarantees()`:

|                              | Android `WorkManager` | iOS 18 `BGTaskScheduler` | macOS 15 `NSBackgroundActivityScheduler` |
| ---------------------------- | --------------------- | ------------------------ | ---------------------------------------- |
| `survivesProcessDeath`       | true                  | true                     | true                                     |
| `survivesReboot`             | true                  | true                     | true                                     |
| `survivesForceQuit`          | **true**              | **false**                | true                                     |
| `honoursWallClock`           | approx                | **false** (hint only)    | approx                                   |
| `supportsRetryBackoff`       | true                  | true (emulated)          | true (emulated)                          |
| `cancelsInFlight`            | **true**              | **false**                | true                                     |
| `minimumPeriodicInterval`    | 15 min                | 15 min recommended       | 1 sec                                    |
| `maxConcurrentTasks`         | unbounded-ish         | ~1000                    | unbounded-ish                            |

iOS-specific: when the user **force-quits the app from the App Switcher**, all background tasks stop firing until the user launches the app again. That's Apple's design. Surface it in your UX ("Open the app daily so we can sync."). See [Force-quit caveat](https://happycodelucky.github.io/backgrounder-kmp/platforms/force-quit/).

`WorkRequest(ephemeral = true)` marks work that must be re-scheduled by app code after init; every cold start cancels leftover ephemeral jobs before any worker can dispatch. See [The `ephemeral` flag](https://happycodelucky.github.io/backgrounder-kmp/concepts/ephemeral/).

---

## Documentation

- **[Getting started](https://happycodelucky.github.io/backgrounder-kmp/getting-started/)** — install, configure, run one job.
- **[Recipes](https://happycodelucky.github.io/backgrounder-kmp/recipes/one-shot/)** — one-shot, run now, periodic, cancel, retry, input, network, monitoring, testing, the iOS plist plugin.
- **[Concepts](https://happycodelucky.github.io/backgrounder-kmp/concepts/architecture/)** — architecture, task ids, worker context and DI, guarantees, opportunistic dispatch.
- **[Platforms](https://happycodelucky.github.io/backgrounder-kmp/platforms/android/)** — per-platform launch sequences and what runs where.

---

## Build & test

[`mise`](https://mise.jdx.dev) pins the JDK, Gradle bootstrap, Python (mkdocs), and `gh` — see [`mise.toml`](./mise.toml). One-time bootstrap:

```bash
brew install mise
mise trust && mise install
```

Common tasks:

```bash
mise run check          # all unit tests across iOS sim, macOS native, Android JVM, the Gradle plugin
mise run build:ios      # iOS device + Apple Silicon simulator debug frameworks, SKIE-enhanced
mise run xcframework    # release Backgrounder.xcframework (KMMBridge artifact)

# Raw Gradle equivalents, for reference:
./gradlew check
./gradlew :backgrounder:linkDebugFrameworkIosArm64
./gradlew :backgrounder:assembleBackgrounderXCFramework
```

Background tasks don't fire automatically in the iOS Simulator. Drive them from LLDB while paused, using the tick identifier for periodics and the per-task id for one-shots:

```
(lldb) e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"dev.example.app.backgrounder-tick"]
```

---

## Repository conventions

- **Versions** (`gradle/libs.versions.toml`) are the single source of truth. Web-search before bumping any dependency (CLAUDE.md §2). Kotlin is pinned at the highest version SKIE supports.
- Every public method carries `@ObjCName(swiftName = ...)` so the call site reads like Swift. `suspend fun`s reachable from Swift do **not** include `CancellationException` in `@Throws` — SKIE bridges cancellation through Swift's native `CancellationError` automatically (CLAUDE.md §8).
- `internal` by default; widen visibility only when needed (CLAUDE.md §3).
- **DI is a user choice.** The library uses constructor injection internally and a factory-closure seam for user code; no DI container is required.

See [`CLAUDE.md`](./CLAUDE.md) for the full project conventions.
