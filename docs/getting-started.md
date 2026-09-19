# Getting started

Goal: install Backgrounder, configure it at app launch, and run one `BackgroundWorker` from `commonMain` on your platform's native scheduler.

## 1. Install

Add the library from `commonMain`. KMP resolves the Android AAR, the `jvm` JAR, and the `iosArm64` / `iosSimulatorArm64` / `macosArm64` klibs for you:

```kotlin title="shared/build.gradle.kts"
plugins {
    kotlin("multiplatform")
    id("com.happycodelucky.backgrounder") version "{{ version }}"   // iOS apps: see below
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.happycodelucky.backgrounder:backgrounder:{{ version }}")
        }
    }
}

backgrounder {
    iosInfoPlist = file("../iOSApp/App/Info.plist")
    iosBundleIdentifier = "dev.example.app"
}
```

The `backgrounder { }` block and the plugin line matter only if you ship iOS. iOS refuses to fire any background task whose identifier is not listed in the app's `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`, and it fails silently when one is missing. The plugin's `updateBackgrounderInfoPlist` task writes that list from the ids you annotate with `@BGTaskSchedulerPermittedIdentifier` (every id you may schedule as a one-shot) plus the tick identifier derived from `iosBundleIdentifier`. Run it from an Xcode run-script phase or a pre-commit hook. Details in [Generate the iOS permitted identifiers](recipes/ios-permitted-identifiers.md); Android-only and pure-Swift installation, and the platform floors, are in [Installation](installation.md).

## 2. Configure the launch sequence

There is one `BackgroundTaskManager` per process, reachable anywhere as `BackgroundTaskManager.shared`. On Android the library builds it before `Application.onCreate` through an `androidx.startup` initializer; on iOS, macOS, and the JVM it builds itself on first access. The launch sequence is two steps: **`register`** every worker factory, then **`start`** to finalize.

The factory closure you pass to `register(...)` is where DI happens — pass a closure that resolves dependencies from whatever DI graph your app already uses (Koin, Hilt, kotlin-inject, hand-wired). Backgrounder doesn't require or ship a DI container.

=== "Android"

    ```kotlin title="MyApp.kt — Application.onCreate"
    import androidx.work.Configuration
    import com.happycodelucky.backgrounder.BackgroundTaskManager
    import com.happycodelucky.backgrounder.androidWorkerFactory
    import com.happycodelucky.backgrounder.shared

    class MyApp : Application(), Configuration.Provider {
        override fun onCreate() {
            super.onCreate()

            // 1. BackgroundTaskManager.shared already exists: the library's androidx.startup
            //    initializer built it before onCreate ran. If your manifest removes
            //    the InitializationProvider entirely, call
            //    BackgroundTaskManager.configure(application = this) here first.

            // 2. Register every worker factory. The closure is yours — resolve
            //    dependencies however you like (Koin, Hilt, hand-wired).
            BackgroundTaskManager.shared.register(SyncWorker.ID) {
                SyncWorker(repo = appGraph.repository)
            }

            // 3. Start. Sweeps ephemeral work left over from the previous process,
            //    seals the registry, and flips the ready gate so workers enqueued
            //    before this point may now dispatch.
            BackgroundTaskManager.shared.start()
        }

        // Tell WorkManager to use Backgrounder's WorkerFactory. Required.
        override val workManagerConfiguration: Configuration get() =
            Configuration.Builder()
                .setWorkerFactory(BackgroundTaskManager.shared.androidWorkerFactory())
                .build()
    }
    ```

    Add to `AndroidManifest.xml` to disable WorkManager's default auto-init (required because we install our `WorkerFactory` via `Configuration.Provider`). Keep the provider itself: BackgroundTaskManager's own initializer rides on it.

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

=== "iOS"

    ```swift title="AppDelegate.swift"
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

    Add the tick identifier (mandatory) plus one entry per `WorkRequest.OneTime` task id you schedule to your app's `Info.plist`. Periodic ids do **not** need their own entries — the tick handles them. The [Gradle plugin](recipes/ios-permitted-identifiers.md) can write this array for you.

    ```xml
    <key>BGTaskSchedulerPermittedIdentifiers</key>
    <array>
        <string>dev.example.app.backgrounder-tick</string>  <!-- mandatory: the default tick, "<bundle id>.backgrounder-tick" -->
        <string>dev.example.app.upload</string>             <!-- one-shot WorkRequest.OneTime -->
    </array>
    ```

=== "macOS"

    ```swift title="AppDelegate.swift"
    final class AppDelegate: NSObject, NSApplicationDelegate {
        func applicationDidFinishLaunching(_ notification: Notification) {
            // BackgroundTaskManager.shared builds itself on first access.
            BackgroundTaskManager.shared.register(taskId: SyncWorker.companion.ID) {
                SyncWorker(repo: AppGraph.shared.repository)
            }
            BackgroundTaskManager.shared.start()
        }

        func applicationWillTerminate(_ notification: Notification) {
            // Cancel the scheduler's coroutine scope cleanly.
            BackgroundTaskManager.shared.shutdown()
        }
    }
    ```

    No `Info.plist` work needed; `NSBackgroundActivityScheduler` owns scheduling lifetime.

=== "JVM"

    ```kotlin title="Main.kt"
    fun main() {
        // BackgroundTaskManager.shared builds itself on first access.
        BackgroundTaskManager.shared.register(SyncWorker.ID) {
            SyncWorker(repo = appGraph.repository)
        }
        BackgroundTaskManager.shared.start()

        Runtime.getRuntime().addShutdownHook(
            Thread { BackgroundTaskManager.shared.shutdown() },
        )

        // … run your app …
    }
    ```

    Scheduling is in-process (library-owned coroutines), so schedules die with the JVM — re-schedule from your init path at each launch. See [Platforms → JVM](platforms/jvm.md).

## 3. Run background work

### Define a `BackgroundWorker` in `commonMain`

Implement the single-method `BackgroundWorker` interface. Workers are *built by a factory at app launch* — not instantiated by reflection — so they receive their dependencies through their constructor.

```kotlin title="commonMain/SyncWorker.kt"
import com.happycodelucky.backgrounder.BGTaskSchedulerPermittedIdentifier
import com.happycodelucky.backgrounder.BackgroundWorker
import com.happycodelucky.backgrounder.WorkResult
import com.happycodelucky.backgrounder.WorkerContext

class SyncWorker(
    private val repo: MyRepository,
) : BackgroundWorker {
    override suspend fun execute(context: WorkerContext): WorkResult {
        return try {
            repo.sync()
            WorkResult.Success
        } catch (t: Throwable) {
            // The library will retry per WorkRequest.backoff up to maxAttempts.
            WorkResult.Retry
        }
    }

    companion object {
        @BGTaskSchedulerPermittedIdentifier const val ID = "dev.example.app.sync"
    }
}
```

### Schedule it

From anywhere in your app, through `BackgroundTaskManager.shared`. Inject it into your DI graph if you prefer (`single { BackgroundTaskManager.shared }` in Koin); nothing needs to be passed around.

```kotlin
import kotlin.time.Duration.Companion.seconds

BackgroundTaskManager.shared.schedule(
    WorkRequest.OneTime(
        taskId = SyncWorker.ID,
        constraints = WorkConstraints(networkRequired = NetworkRequirement.Any),
        backoff = BackoffPolicy.exponential(initialDelay = 30.seconds, maxAttempts = 5),
    ),
)
```

The platform scheduler will dispatch the worker when its constraints are satisfied. For work that must happen *right now* and survive the user backgrounding the app, skip the worker and the registry entirely:

```kotlin
val saved: SavedDocument = BackgroundTaskManager.shared.runNow(SaveTask.ID) { repo.save(draft) }
```

 On Android it'll fire once the device is on a network. On iOS it'll fire when the system feels like it (after `earliestBeginDate`); see [Guarantees](concepts/guarantees.md) for what each platform actually promises.

## What's next

- **[Recipes](recipes/one-shot.md)** — task-oriented "how to do X" pages.
- **[Recipes → Run now](recipes/run-now.md)** — `BackgroundTaskManager.runNow<R>(taskId) { … }` for "do this work in the background right now and let me `await` the typed result." Different from scheduled work — no constraints, no retries, the lambda *is* the work.
- **[Concepts → Worker context & DI](concepts/worker-context-and-di.md)** — the factory pattern in depth, including Koin / Hilt / hand-wired examples.
- **[Concepts → Ephemeral flag](concepts/ephemeral.md)** — defending against the "ran before init" Android foot-gun.
- **[Platforms → Force-quit caveat (iOS)](platforms/force-quit.md)** — read before shipping iOS.
