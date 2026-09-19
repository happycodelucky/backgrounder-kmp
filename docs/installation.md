# Installation

Backgrounder publishes to Maven Central as a Kotlin Multiplatform artifact.
The Android AAR and the JVM JAR are consumed directly from Gradle. Apple
consumers come in via the same artifact's `iosArm64`, `iosSimulatorArm64`, and
`macosArm64` klibs — i.e. by depending on `:backgrounder` from a KMP project.

Pure-Swift apps (no Kotlin Multiplatform in the project) consume Backgrounder
through Swift Package Manager — a prebuilt, SKIE-enhanced `Backgrounder.xcframework`
delivered as a GitHub Release asset (see [Apple-side SPM](#apple-side-spm) below).

## Platform floors

| Platform  | Floor                                                 |
| --------- | ----------------------------------------------------- |
| Android   | `arm64-v8a`, minSdk 30                                |
| iOS       | iOS 18.0                                              |
| macOS     | macOS 15.0 (Apple Silicon)                            |
| JVM       | Java 21 (desktop / server, any architecture)          |
| Toolchain | Kotlin 2.3.x (K2), Gradle 9.x, AGP 9.x, JVM target 21 |

These are deliberately tight floors — see [Concepts → Architecture](concepts/architecture.md)
for why we don't ship Catalyst, x86, watchOS, or tvOS.

## Gradle — Kotlin Multiplatform consumer

If your app is itself a Kotlin Multiplatform project, depend on the
`backgrounder` artifact from `commonMain` and let KMP pull the right
per-target slice automatically. Maven Central is on the default repository
list, so no `repositories { }` block changes are needed.

=== "Version catalog (`libs.versions.toml`)"

    ```toml
    [versions]
    backgrounder = "{{ version }}"

    [libraries]
    backgrounder = { module = "com.happycodelucky.backgrounder:backgrounder", version.ref = "backgrounder" }
    ```

=== "Kotlin DSL (`build.gradle.kts`)"

    ```kotlin
    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation(libs.backgrounder)
            }
        }
    }
    ```

Backgrounder pulls `kotlinx.coroutines`, `kotlinx.serialization`,
`multiplatform-settings`, `kermit`, and (on Android only)
`androidx.work-runtime` + `androidx.startup-runtime` transitively.
**No DI container is required** — the library uses constructor injection
internally and a factory-closure seam for user code, so any DI graph you
already use plugs in cleanly.

## Gradle plugin (optional)

The `com.happycodelucky.backgrounder` Gradle plugin keeps the iOS
`BGTaskSchedulerPermittedIdentifiers` array in sync with the
`@BGTaskSchedulerPermittedIdentifier` constants in your shared module. Apply it to the module
that declares your workers:

```kotlin
plugins {
    id("com.happycodelucky.backgrounder") version "{{ version }}"
}

backgrounder {
    iosInfoPlist = file("../iOSApp/App/Info.plist")
}
```

It resolves from Maven Central alongside the library. See
[Generate the iOS permitted identifiers](recipes/ios-permitted-identifiers.md).

## Android-only consumer

If your app is Android-only (not a KMP project), depend on the published
Android artifact:

```kotlin
dependencies {
    implementation("com.happycodelucky.backgrounder:backgrounder-android:{{ version }}")
}
```

You'll need to install Backgrounder's `WorkerFactory` via
`Configuration.Provider` (mandatory; see [Platforms → Android](platforms/android.md)
for the full launch-sequence snippet) and disable WorkManager's default
auto-init in your `AndroidManifest.xml`:

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

## iOS / macOS consumer

If you're working from a KMP project, the iOS and macOS targets are consumed
transparently via the `kotlinMultiplatform` metadata published alongside the
Android AAR. Depend on `:backgrounder` from `commonMain` exactly as shown in
the [Gradle — Kotlin Multiplatform consumer](#gradle-kotlin-multiplatform-consumer)
section above; KMP resolves the `iosArm64`, `iosSimulatorArm64`, and
`macosArm64` slices for you. The Swift-facing framework is produced by your
own project's framework build, not the library's.

Add the tick identifier plus one entry per `WorkRequest.OneTime` task id you
schedule to your app's `Info.plist`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>dev.example.app.background-tick</string>  <!-- mandatory: matches tickIdentifier -->
    <string>dev.example.app.sync</string>             <!-- one-shot WorkRequest.OneTime -->
</array>
```

The library logs an error during `backgrounder.start()` for any task
id missing from this list — failing close to the cause rather than at first
`schedule()`.

## Apple-side SPM

Pure-Swift apps consume Backgrounder through Swift Package Manager. In Xcode,
**File → Add Package Dependencies…**, enter this repo's URL, and pin to a
version tag:

```
https://github.com/happycodelucky/backgrounder.git
```

The tagged `Package.swift` hands SPM a prebuilt `Backgrounder.xcframework`
(iosArm64 + iosSimulatorArm64 + macosArm64, SKIE-enhanced) referenced by URL
+ sha256 checksum from a GitHub Release asset. No Kotlin toolchain, no Gradle,
no authentication. Under the hood this is built by
[KMMBridge](https://kmmbridge.touchlab.co/) — GitHub *Releases*, not GitHub
*Packages*, because Packages requires a PAT even to download from public repos.

!!! note "First release"
    SPM consumption starts at the first published version tag. Until the first
    release runs, the committed `Package.swift` points at a local build path
    (for the in-repo sample apps).

If you're working from a KMP project instead, the iOS / macOS targets are
consumed transparently via the published `kotlinMultiplatform` metadata, as
described above — no XCFramework involved.

## Local development override

When developing against an unpublished version of Backgrounder, flip the root
`Package.swift` to a local `.binaryTarget(path:)` and rebuild the debug
`XCFramework` in one step:

```bash
# In the Backgrounder repo:
mise run spm:dev          # → ./gradlew :backgrounder:spmDevBuild
```

This rewrites `Package.swift` to point at
`backgrounder/build/XCFrameworks/debug/Backgrounder.xcframework`, so the in-repo
sample apps (and any app consuming this repo via `.package(path: "..")`) pick up
local Kotlin edits — Xcode rebuilds against the new binary on the next build, no
publish step needed. The rewrite is a working-tree convenience; **never commit
it.** Restore the committed (released) manifest with `mise run spm:restore`.

For a KMP consumer, `./gradlew :backgrounder:publishToMavenLocal` plus
`mavenLocal()` on the consumer's repository list works the same way.

## Verify the install

After the launch sequence in [Getting started](getting-started.md) is in
place, this snippet should compile and run on every platform:

```kotlin
println(backgrounder.guarantees())
```

Output (truncated, platform-dependent — see [Guarantees](concepts/guarantees.md)):

```
SchedulerGuarantees(survivesProcessDeath=true, survivesReboot=true, survivesForceQuit=false, ...)
```
