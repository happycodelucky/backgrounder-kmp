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
  <img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin 2.3">
  <a href="https://github.com/happycodelucky/backgrounder/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/happycodelucky/backgrounder/ci.yml?style=for-the-badge&label=ci" alt="CI"></a>
  <a href="https://github.com/happycodelucky/backgrounder/actions/workflows/docs.yml"><img src="https://img.shields.io/github/actions/workflow/status/happycodelucky/backgrounder/docs.yml?style=for-the-badge&label=docs" alt="Docs"></a>
  <a href="https://github.com/happycodelucky/backgrounder/releases/latest"><img src="https://img.shields.io/github/v/release/happycodelucky/backgrounder?style=for-the-badge" alt="Release"></a>
</p>

---

Backgrounder wraps each platform's background-scheduling primitive behind one API:

- **Android**: Jetpack `WorkManager` (one-shot + periodic, with constraints, retry, expedited).
- **iOS 18+**: `BGTaskScheduler` (one-shot + library-emulated periodic; force-quit caveat documented).
- **macOS 15+**: Foundation's `NSBackgroundActivityScheduler` (one-shot + native periodic).
- **JVM 21+** (desktop / server): library-owned coroutines (one-shot + periodic; in-process, nothing survives the process).

Full documentation [here](https://happycodelucky.github.io/backgrounder-kmp/).

---

## Installation

Backgrounder publishes to Maven Central. From a Kotlin Multiplatform project,
depend on the artifact from `commonMain` — KMP resolves the right per-target
slice (Android AAR, `jvm` JAR, `iosArm64`, `iosSimulatorArm64`, `macosArm64`)
for you:

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

Android-only consumers depend on the Android artifact directly:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.happycodelucky.backgrounder:backgrounder-android:0.9.0")
}
```

Pure-Swift apps that don't use Kotlin Multiplatform aren't supported yet — a
native Swift Package Manager distribution is on the roadmap. See the
[Installation guide](https://happycodelucky.github.io/backgrounder/installation/)
for platform floors, the Apple-side SPM roadmap, and the local-development
override.

---

## Usage

```kotlin
// commonMain
class SyncWorker(private val repo: MyRepository) : BackgroundWorker {
    override suspend fun execute(context: WorkerContext): WorkResult {
        return try {
            repo.sync()
            WorkResult.Success
        } catch (t: Throwable) {
            WorkResult.Retry
        }
    }

    companion object {
        val ID = TaskId("dev.example.app.sync")
    }
}
```

The library never instantiates your worker by reflection — you give it a factory at app launch:

```kotlin
// Register a single worker
backgrounder.register(SyncWorker.ID) { SyncWorker(repo = appGraph.repo) }

// Or register many workers at once with a BackgroundWorkerFactory
backgrounder.register(appModule.workerFactory())
```

The closure — or factory — is yours: resolve dependencies through Koin, Hilt, kotlin-inject, hand-wired singletons — whatever your app already uses. A fresh worker is built per invocation with all its dependencies wired.

Then schedule from anywhere:

```kotlin
backgrounder.schedule(
    WorkRequest.OneTime(
        taskId = SyncWorker.ID,
        constraints = WorkConstraints(networkRequired = NetworkRequirement.Any),
        backoff = BackoffPolicy.exponential(initialDelay = 30.seconds, maxAttempts = 5),
    ),
)
```

`networkRequired` is honoured everywhere — Android holds the worker via WorkManager's native constraint gating; iOS, macOS, and the JVM use a library-managed pre-execution reachability gate (powered by [reachable](https://github.com/happycodelucky/reachable)) that waits up to 5 seconds before short-circuiting to `WorkResult.Retry`. See [Recipes → Require a network connection](https://happycodelucky.github.io/backgrounder/recipes/network-required/).

Or for "do this work in the background **right now** and give me back the typed result" — no constraints, no retries, structured `await` — use `runNow`:

```kotlin
val saved: SavedDocument = backgrounder.runNow(saveTaskId) {
    repo.save(draft)
}
```

`runNow` runs on the platform's real background primitive so the work survives if the user backgrounds the app mid-call — `UIApplication.beginBackgroundTask` on iOS, `WorkManager` on Android, a library scope on macOS and the JVM. See the [Run now recipe](https://happycodelucky.github.io/backgrounder/recipes/run-now/) for the full contract.

---

## Launch sequence — Android

`Application.onCreate` does **three** things — *create*, *register*, *start* — plus one mandatory wiring: install Backgrounder's `WorkerFactory` via `Configuration.Provider`.

```kotlin
import androidx.work.Configuration
import com.happycodelucky.backgrounder.Backgrounder
import com.happycodelucky.backgrounder.androidWorkerFactory
import com.happycodelucky.backgrounder.create

class MyApp : Application(), Configuration.Provider {
    lateinit var backgrounder: Backgrounder

    override fun onCreate() {
        super.onCreate()

        // 1. Construct. Eagerly sweeps ephemeral work from prior runs.
        backgrounder = Backgrounder.create(application = this)

        // 2. Register every BackgroundWorker factory.
        backgrounder.register(SyncWorker.ID) { SyncWorker(repo = appGraph.repo) }

        // 3. Start. Seals the registry; flips the ready gate.
        backgrounder.start()
    }

    // Tell WorkManager to use Backgrounder's WorkerFactory. Required.
    override val workManagerConfiguration: Configuration get() =
        Configuration.Builder()
            .setWorkerFactory(backgrounder.androidWorkerFactory())
            .build()
}
```

Add to your app's `AndroidManifest.xml` to disable WorkManager's default auto-init (mandatory whenever you implement `Configuration.Provider`):

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

---

## Launch sequence — iOS

```swift
@main
final class AppDelegate: NSObject, UIApplicationDelegate {
    // Pick a tick identifier in your app's reverse-DNS namespace. The library
    // uses it as the BGAppRefreshTaskRequest that wakes periodic dispatch in
    // the background. Periodic task ids do not need their own Info.plist
    // entries — the tick handles them.
    let backgrounder = Backgrounder.companion.create(
        tickIdentifier: "dev.example.app.background-tick"
    )

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?,
    ) -> Bool {
        backgrounder.register(taskId: SyncWorker.companion.ID) {
            SyncWorker(repo: AppGraph.shared.repository)
        }
        backgrounder.start()
        return true
    }
}
```

Add the tick identifier (mandatory) plus one entry per `WorkRequest.OneTime` task id you schedule to your app's `Info.plist`. Periodic ids do **not** need their own entries.

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>dev.example.app.background-tick</string>  <!-- mandatory: matches tickIdentifier above -->
    <string>dev.example.app.upload</string>           <!-- one-shot WorkRequest.OneTime -->
</array>
```

A missing tick identifier is reported with a Kermit error during `backgrounder.start()` (close to the cause; not at first `schedule()`). Missing one-shot ids surface as warnings — the library can't tell at registration time which ids will be used as one-shots vs periodics.

See [docs/platforms/ios.md](docs/platforms/ios.md) for how the foreground/background dispatcher works, the coalescing contract, and the per-path execution windows.

### iOS testing

Background tasks don't fire automatically in the iOS Simulator. Drive them from LLDB while paused:

```
(lldb) e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"dev.example.app.background-tick"]
(lldb) e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateExpirationForTaskWithIdentifier:@"dev.example.app.background-tick"]
```

Use the tick identifier (not the per-task id) to simulate background dispatch of periodics. For one-shots, use the per-task id you scheduled. The foreground dispatch loop runs normally regardless and doesn't need LLDB.

---

## Launch sequence — macOS

```swift
@main
final class AppDelegate: NSObject, NSApplicationDelegate {
    let backgrounder = Backgrounder.companion.create()

    func applicationDidFinishLaunching(_ notification: Notification) {
        backgrounder.register(taskId: SyncWorker.companion.ID) {
            SyncWorker(repo: AppGraph.shared.repository)
        }
        backgrounder.start()
    }

    func applicationWillTerminate(_ notification: Notification) {
        backgrounder.shutdown()
    }
}
```

`NSBackgroundActivityScheduler` owns scheduling lifetime, so there's no force-quit caveat — periodic schedules survive cleanly.

---

## What each platform actually guarantees

Read at runtime via `backgrounder.guarantees()`:

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

iOS-specific: when the user **force-quits the app from the App Switcher**, all background tasks stop firing until the user launches the app again. That's Apple's design — we can't paper over it. Surface this in your UX (e.g. "Open the app daily so we can sync.").

---

## The `ephemeral` flag

`WorkRequest(ephemeral = true)` declares "this work must be re-scheduled by app code after init; do not run it from a state I didn't deliberately put it in." On every cold app start, the library cancels every ephemeral job *before* any worker can dispatch.

Use it when the worker depends on app state initialised after `Application.onCreate` / `application(_:didFinishLaunchingWithOptions:)`. The sweep happens at:
- **Android**: inside `Backgrounder.create(application)`, before any worker can dispatch.
- **iOS / macOS**: top of `backgrounder.start()`.

On Android, the sweep is augmented by a per-instance ready gate: if WorkManager somehow fires an ephemeral worker before `backgrounder.start()` has been called, the worker returns `Failure("dispatched before ephemeralReady")` immediately rather than running with stale state.

---

## Build & test

[`mise`](https://mise.jdx.dev) pins the JDK, Gradle bootstrap, Python (mkdocs), and `gh` — see [`mise.toml`](./mise.toml). One-time bootstrap:

```bash
brew install mise
mise trust && mise install
```

Common tasks:

```bash
mise run check          # all unit tests across iOS sim, macOS native, Android JVM
mise run build:ios      # iOS device + Apple Silicon simulator debug frameworks, SKIE-enhanced
mise run xcframework    # release Backgrounder.xcframework (KMMBridge artifact)

# Raw Gradle equivalents, for reference:
./gradlew :backgrounder:check
./gradlew :backgrounder:linkDebugFrameworkIosArm64
./gradlew :backgrounder:assembleBackgrounderXCFramework
```

`mise run check` runs:
- `iosSimulatorArm64Test` — kotlin-test + Turbine + multiplatform-settings test impl
- `macosArm64Test` — same suite, native macOS
- `testAndroidHostTest` — JVM-side tests via Robolectric-free pure mappers

---

## Repository conventions

- **Versions** (`gradle/libs.versions.toml`) are the single source of truth. Web-search before bumping any dependency (CLAUDE.md §2). Kotlin is pinned at the highest version SKIE supports — currently 2.3.20 with SKIE 0.10.11.
- Every public method carries `@ObjCName(swiftName = ...)` so the call site reads like Swift. `suspend fun`s reachable from Swift do **not** include `CancellationException` in `@Throws` — SKIE bridges cancellation through Swift's native `CancellationError` automatically (CLAUDE.md §8).
- `internal` by default; widen visibility only when needed (CLAUDE.md §3).
- **DI is a user choice.** The library uses constructor injection internally and a factory-closure seam for user code; no DI container is required.

See [`CLAUDE.md`](./CLAUDE.md) for the full project conventions.
