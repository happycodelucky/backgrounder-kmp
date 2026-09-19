# Generate the iOS permitted identifiers

Every task id you schedule as a `WorkRequest.OneTime` on iOS, plus the tick identifier, must appear in the app's `BGTaskSchedulerPermittedIdentifiers` `Info.plist` array. When the two drift apart iOS silently never fires the task. The Backgrounder Gradle plugin removes the drift by generating the array from your code.

## 1. Annotate each id that belongs in the plist

This is an iOS-only concern and it says nothing about how the work runs. Two kinds of id belong in the array: the tick identifier, and every id you may schedule as a `WorkRequest.OneTime`. If you rely on the default tick identifier (you never call `Backgrounder.create(tickIdentifier:)`), set `iosBundleIdentifier` in the plugin block instead of annotating anything and the plugin adds `<bundle id>.backgrounder-tick` for you. Periodic ids and `runNow` ids never reach `BGTaskScheduler`, so they don't need it; annotating them anyway is harmless, since a surplus entry costs nothing, while a missing entry means iOS silently never fires the task.

Declare each such id once as a `const val String` and mark it `@BGTaskSchedulerPermittedIdentifier`:

```kotlin
import com.happycodelucky.backgrounder.BGTaskSchedulerPermittedIdentifier

class SyncWorker(private val repo: Repository) : BackgroundWorker {
    override suspend fun execute(context: WorkerContext): WorkResult = ...

    companion object {
        @BGTaskSchedulerPermittedIdentifier const val ID = "dev.example.app.sync"
    }
}

class UploadWorker(...) : BackgroundWorker {
    companion object {
        @BGTaskSchedulerPermittedIdentifier const val ID = "dev.example.app.upload"
    }
}
```

`const val` is legal at top level, in an `object`, or in a `companion object`. Constants built from other constants fold at compile time, so `"$PREFIX.sync"` works as long as `PREFIX` is itself `const`.

## 2. Apply the plugin to the shared module

```kotlin
// shared/build.gradle.kts
plugins {
    kotlin("multiplatform")
    id("com.happycodelucky.backgrounder") version "{{ version }}"
}

backgrounder {
    iosInfoPlist = file("../iOSApp/App/Info.plist")
}
```

The plugin resolves from Maven Central, so no `pluginManagement` changes are needed.

## 3. Run it

```bash
./gradlew updateBackgrounderInfoPlist
```

Two tasks run:

| Task | What it does |
| --- | --- |
| `collectBackgroundTaskIds` | Compiles the module's JVM slice, scans the class files for `@BGTaskSchedulerPermittedIdentifier` constants, validates them, and writes `build/backgrounder/task-ids.txt`. Cacheable and incremental. |
| `updateBackgrounderInfoPlist` | Rewrites the `BGTaskSchedulerPermittedIdentifiers` array in the configured plist from that manifest. Replaces only that one key and array; every other byte of the file, including its indentation style, is preserved. Inserts the key if it's missing. |

Wire it into your iOS build however suits you. A common choice is an Xcode run-script phase before **Compile Sources** that calls the Gradle task, so the plist is current on every build. Another is a pre-commit hook. The rewrite is idempotent, so running it more often than needed costs nothing.

## Configuration

```kotlin
backgrounder {
    iosInfoPlist = file("../iOSApp/App/Info.plist")   // unset → plist task is skipped
    iosBundleIdentifier = "dev.example.app"           // adds the default tick "<bundle id>.backgrounder-tick"
    scanCompileTask = "compileKotlinJvm"               // default: first of compileKotlinJvm, compileAndroidMain, compileKotlin
    manifest = layout.buildDirectory.file("backgrounder/task-ids.txt")
    warnOnNonReverseDns = true                         // warning only, never an error
}
```

## What can go wrong

- **`@BGTaskSchedulerPermittedIdentifier requires a const val String`.** The annotation is on a plain `val`. Only `const` initializers are folded into the class file where the scanner can read them.
- **`id '…' is declared more than once`.** Two constants carry the same string. Task ids are keys; the build fails so you pick one.
- **`is blank` / `has leading or trailing whitespace` / `contains control characters`.** The same rules `Backgrounder` enforces at runtime, caught earlier. See [Task ids](../concepts/task-ids.md).
- **`does not follow the reverse-DNS convention`.** A warning, not a failure. Set `warnOnNonReverseDns = false` if your ids intentionally use another shape.
- **`none of compileKotlinJvm, compileAndroidMain, compileKotlin exist`.** The module has no JVM-family target for the scanner to read. Add `jvm()` or an Android target, or point `scanCompileTask` at a task whose output contains the class files.
- **Ids registered from Swift are invisible.** The scanner only sees Kotlin constants. Workers registered directly from Swift code still need their plist entry by hand, and the runtime check at `start()` remains the backstop for them.
