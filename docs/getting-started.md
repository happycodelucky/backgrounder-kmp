# Getting started

Goal: a single working `BackgroundWorker` invocation from `commonMain`, dispatched by your platform's native scheduler.

There are three steps regardless of platform: *create*, *register*, *start*. The concrete code per step changes a bit between Android, iOS, macOS, and the JVM — pick the tab that matches the platform you're building for first.

## 1. Add Backgrounder to your build

See [Installation](installation.md) for the version-catalog snippet and the platform-floor table. The short version is: add `com.happycodelucky.backgrounder:backgrounder` to your `commonMain` dependencies.

## 2. Define a `BackgroundWorker` in `commonMain`

Implement the single-method `BackgroundWorker` interface. Workers are *built by a factory at app launch* — not instantiated by reflection — so they receive their dependencies through their constructor.

```kotlin title="commonMain/SyncWorker.kt"
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
        const val ID = "dev.example.app.sync"
    }
}
```

## 3. Wire up the launch sequence

The library exposes a single `Backgrounder` instance you construct at app launch and hold for the app's lifetime. The three steps are: **`create`** the instance, **`register`** every worker factory, then **`start`** to finalize.

The factory closure you pass to `register(...)` is where DI happens — pass a closure that resolves dependencies from whatever DI graph your app already uses (Koin, Hilt, kotlin-inject, hand-wired). Backgrounder doesn't require or ship a DI container.

=== "Android"

    ```kotlin title="MyApp.kt — Application.onCreate"
    import androidx.work.Configuration
    import com.happycodelucky.backgrounder.Backgrounder
    import com.happycodelucky.backgrounder.androidWorkerFactory
    import com.happycodelucky.backgrounder.create

    class MyApp : Application(), Configuration.Provider {
        lateinit var backgrounder: Backgrounder

        override fun onCreate() {
            super.onCreate()

            // 1. Construct. This eagerly sweeps any ephemeral work from prior
            //    runs; it does NOT trigger WorkManager.getInstance(...) yet.
            backgrounder = Backgrounder.create(application = this)

            // 2. Register every worker factory. The closure is yours — resolve
            //    dependencies however you like (Koin, Hilt, hand-wired).
            backgrounder.register(SyncWorker.ID) {
                SyncWorker(repo = appGraph.repository)
            }

            // 3. Start. Seals the registry; flips the ready gate so workers
            //    enqueued before this point may now dispatch.
            backgrounder.start()
        }

        // Tell WorkManager to use Backgrounder's WorkerFactory. Required.
        override val workManagerConfiguration: Configuration get() =
            Configuration.Builder()
                .setWorkerFactory(backgrounder.androidWorkerFactory())
                .build()
    }
    ```

    Add to `AndroidManifest.xml` to disable WorkManager's default auto-init (required because we install our `WorkerFactory` via `Configuration.Provider`):

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
        // Pick a tick identifier in your app's reverse-DNS namespace; it must
        // match the entry you add to Info.plist below. The library uses it as
        // the BGAppRefreshTaskRequest that wakes periodic dispatch in the
        // background. Periodic task ids do not need their own entries.
        let backgrounder = Backgrounder.companion.create(
            tickIdentifier: "dev.example.app.background-tick"
        )

        func application(
            _ application: UIApplication,
            didFinishLaunchingWithOptions options:
                [UIApplication.LaunchOptionsKey: Any]?,
        ) -> Bool {
            // 1. (already constructed as a stored property above)

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

    Add the tick identifier (mandatory) plus one entry per `WorkRequest.OneTime` task id you schedule to your app's `Info.plist`. Periodic ids do **not** need their own entries — the tick handles them.

    ```xml
    <key>BGTaskSchedulerPermittedIdentifiers</key>
    <array>
        <string>dev.example.app.background-tick</string>  <!-- mandatory: matches tickIdentifier above -->
        <string>dev.example.app.upload</string>           <!-- one-shot WorkRequest.OneTime -->
    </array>
    ```

=== "macOS"

    ```swift title="AppDelegate.swift"
    final class AppDelegate: NSObject, NSApplicationDelegate {
        let backgrounder = Backgrounder.companion.create()

        func applicationDidFinishLaunching(_ notification: Notification) {
            backgrounder.register(taskId: SyncWorker.companion.ID) {
                SyncWorker(repo: AppGraph.shared.repository)
            }
            backgrounder.start()
        }

        func applicationWillTerminate(_ notification: Notification) {
            // Cancel the scheduler's coroutine scope cleanly.
            backgrounder.shutdown()
        }
    }
    ```

    No `Info.plist` work needed; `NSBackgroundActivityScheduler` owns scheduling lifetime.

=== "JVM"

    ```kotlin title="Main.kt"
    fun main() {
        val backgrounder = Backgrounder.create()
        backgrounder.register(SyncWorker.ID) {
            SyncWorker(repo = appGraph.repository)
        }
        backgrounder.start()

        Runtime.getRuntime().addShutdownHook(
            Thread { backgrounder.shutdown() },
        )

        // … run your app …
    }
    ```

    Scheduling is in-process (library-owned coroutines), so schedules die with the JVM — re-schedule from your init path at each launch. See [Platforms → JVM](platforms/jvm.md).

## 4. Schedule

From anywhere in your app — pass the `backgrounder` instance down through your app graph as you would any service. Hold one reference; never re-resolve.

```kotlin
import kotlin.time.Duration.Companion.seconds

backgrounder.schedule(
    WorkRequest.OneTime(
        taskId = SyncWorker.ID,
        constraints = WorkConstraints(networkRequired = NetworkRequirement.Any),
        backoff = BackoffPolicy.exponential(initialDelay = 30.seconds, maxAttempts = 5),
    ),
)
```

The platform scheduler will dispatch the worker when its constraints are satisfied. On Android it'll fire once the device is on a network. On iOS it'll fire when the system feels like it (after `earliestBeginDate`); see [Guarantees](concepts/guarantees.md) for what each platform actually promises.

## What's next

- **[Recipes](recipes/one-shot.md)** — task-oriented "how to do X" pages.
- **[Recipes → Run now](recipes/run-now.md)** — `Backgrounder.runNow<R>(taskId) { … }` for "do this work in the background right now and let me `await` the typed result." Different from scheduled work — no constraints, no retries, the lambda *is* the work.
- **[Concepts → Worker context & DI](concepts/worker-context-and-di.md)** — the factory pattern in depth, including Koin / Hilt / hand-wired examples.
- **[Concepts → Ephemeral flag](concepts/ephemeral.md)** — defending against the "ran before init" Android foot-gun.
- **[Platforms → Force-quit caveat (iOS)](platforms/force-quit.md)** — read before shipping iOS.
