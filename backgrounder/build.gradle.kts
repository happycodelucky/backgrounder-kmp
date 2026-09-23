@file:Suppress("UnstableApiUsage")

/*
 * Backgrounder — :backgrounder module.
 *
 * Headless KMP module: business logic only, no UI dependencies (CLAUDE.md §1, §7).
 * Native targets are ARM-only per CLAUDE.md §1: iosArm64, iosSimulatorArm64,
 * Android arm64-v8a (via the new com.android.kotlin.multiplatform.library
 * plugin), and macosArm64, plus the architecture-neutral jvm target (desktop /
 * server JVMs). No x86 natives, no Catalyst, no watchOS / tvOS, no Linux /
 * Windows *native* targets (Linux/Windows are served by the jvm target).
 */

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.skie)
    alias(libs.plugins.dokka)
    // Vanniktech Maven Publish (CLAUDE.md §9) — publishes signed artifacts to
    // the Sonatype Central Portal. Applies `maven-publish` and `signing`
    // transitively, so we don't apply `maven-publish` separately. The
    // `mavenPublishing { }` block below configures the Central Portal target,
    // POM metadata, and in-memory GPG signing.
    alias(libs.plugins.maven.publish)
    // KMMBridge (CLAUDE.md §9): aggregates the per-target framework binaries
    // declared below into `Backgrounder.xcframework`
    // (build/XCFrameworks/{debug,release}/), publishes the release zip as a
    // GitHub Release asset, and regenerates the root /Package.swift. The
    // `.github` plugin variant is a superset of the core plugin in 1.2.x —
    // applying both produces a duplicate-extension error, so only this one.
    //
    // Do NOT redeclare `XCFramework("Backgrounder")` in the kotlin { } block:
    // KMMBridge auto-creates the aggregator from the framework binaries at
    // config time (it provides `assembleBackgrounder{Debug,Release}XCFramework`),
    // and a second declaration collides on those task names.
    alias(libs.plugins.kmmbridge.github)
}

kotlin {
    // Library code: every public API symbol must carry an explicit visibility
    // modifier (public / internal / private). Without this, a contributor can
    // forget a modifier on a top-level helper and silently widen the published
    // API surface — which becomes a permanent contract once shipped to Maven
    // Central. The compiler now enforces what was previously convention.
    //
    // See https://kotlinlang.org/docs/whatsnew14.html#explicit-api-mode-for-library-authors
    explicitApi()

    // CLAUDE.md §4: applyDefaultHierarchyTemplate. Don't hand-roll source set wiring.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        // Coalesce iosMain + macosMain into a shared "appleMain" intermediate. The
        // BGTask coroutine bridge pattern (SupervisorJob + invokeOnCompletion) is
        // identical between iOS and macOS — see plan §iOS / §macOS.
        common {
            group("apple") {
                withIos()
                withMacos()
            }
        }
    }

    // --- Apple targets (CLAUDE.md §1) ---------------------------------------
    // Static framework binaries with a stable bundle id, one per ARM slice
    // (iosArm64 device, iosSimulatorArm64, macosArm64). KMMBridge aggregates
    // these into `Backgrounder.xcframework` at config time — there is NO
    // explicit `XCFramework("Backgrounder")` declaration here (see the plugins
    // block): KMMBridge provides `assembleBackgrounder{Debug,Release}XCFramework`
    // itself, writing to `build/XCFrameworks/{debug,release}/`, and a second
    // declaration would collide on those task names.
    //
    // Two non-overlapping distribution channels consume these binaries:
    //   * GitHub Releases (KMMBridge) — the SKIE-enhanced XCFramework zip for
    //     pure-Swift SPM consumers. See the `kmmbridge { }` block below.
    //   * Maven Central (vanniktech) does NOT use the XCFramework — it ships
    //     the per-target klibs + `kotlinMultiplatform` metadata so KMP consumers
    //     resolve via `implementation("com.happycodelucky.backgrounder:backgrounder:X.Y.Z")`.
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Backgrounder"
            isStatic = true
            // Pin the bundle id so SKIE doesn't fall back to the framework name.
            binaryOption("bundleId", "com.happycodelucky.backgrounder.shared")
        }
    }

    // --- Android target (CLAUDE.md §1, §4) ----------------------------------
    // Use the new com.android.kotlin.multiplatform.library plugin's android {} block.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    android {
        namespace = "com.happycodelucky.backgrounder"
        compileSdk =
            libs.versions.android.compile.sdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.min.sdk
                .get()
                .toInt()

        // CLAUDE.md §1: arm64-v8a only. The new KMP Android plugin doesn't wire
        // ABI filters directly; consumers' app modules pin the splits. The
        // shared library itself produces all ABIs the build asks for. We test
        // arm64-v8a only; document this in README.

        withHostTestBuilder { /* enables androidUnitTest */ }
    }

    // --- JVM target (desktop / server) ---------------------------------------
    // Architecture-neutral bytecode — the one target where the ARM-only rule
    // has nothing to say. Scheduling is in-process coroutines (see
    // jvmMain/.../CoroutineBackedScheduler.kt); persistence of the ephemeral
    // mirror is java.util.prefs via multiplatform-settings. No SKIE, no
    // KMMBridge — the JVM ships through Maven Central only, like Android.
    jvm()

    // --- JVM toolchain (CLAUDE.md §2: JVM target 21) ------------------------
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        // K2 stable APIs only (CLAUDE.md §3).
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        // Fail builds on stable-API misuse, not just experimental.
        allWarningsAsErrors.set(false) // bump to true once codebase settles.
    }

    // Per-target JVM toolchain knobs — pins the jvm() target's bytecode level
    // (CLAUDE.md §2: JVM target 21). The Android target manages its own level
    // via the android {} block above.
    targets.withType<org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget>().configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)
            implementation(libs.kermit)
            // Drives the pre-execution `WorkConstraints.networkRequired` gate.
            // Apple platforms back this with `nw_path_monitor` (Network framework);
            // Android relies on WorkManager's native gating, but `reachable` is
            // still on commonMain because the public type leaks into builder
            // signatures uniformly across all platforms.
            implementation(libs.reachable)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.multiplatform.settings.test)
            // Upstream-blessed FakeReachability + withFakeReachability install
            // helper. Replaces our hand-rolled commonTest fake; tests interact
            // with `Reachability.shared` directly via the install/uninstall hook.
            implementation(libs.reachable.testing)
        }

        androidMain.dependencies {
            implementation(libs.androidx.work.runtime)
            implementation(libs.androidx.startup.runtime)
            implementation(libs.kotlinx.coroutines.android)
        }

        // appleMain (iOS + macOS) needs the no-arg multiplatform-settings
        // companion artifact for `NSUserDefaultsSettings(NSUserDefaults(suiteName:))`.
        getByName("appleMain").dependencies {
            implementation(libs.multiplatform.settings.no.arg)
        }

        // androidUnitTest source set is created by withHostTestBuilder above.
        // Configure its deps here.
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.work.testing)
            implementation(libs.turbine)
        }
    }
}

skie {
    // SKIE handles the Kotlin → Swift bridge enhancements (CLAUDE.md §8):
    // exhaustive sealed switching, suspend → async/await, Flow → AsyncSequence,
    // default-arg overloads. We keep all defaults on; tighten only when something
    // bites.
    features {
        group {
            // Keep SKIE-generated Swift names visible in stack traces.
            // (Default behaviour, listed for clarity.)
        }
    }
    analytics {
        // Disable opt-in analytics; we'll revisit if useful.
        disableUpload.set(true)
    }
    build {
        // Xcode 26 requires .swiftinterface files in every framework slice before
        // xcodebuild -create-xcframework will accept them (exit 70 otherwise).
        // produceDistributableFramework() enables Swift library evolution so SKIE
        // emits .swiftinterface alongside .swiftmodule, satisfying the requirement
        // for both debug and release XCFramework builds.
        produceDistributableFramework()
    }
}

// --- KMMBridge: XCFramework → GitHub Release asset → SPM (CLAUDE.md §9) -------
//
// Two distribution channels run from this module, and they don't overlap:
//
//   1. Maven Central (`mavenPublishing { }` below) — Android AAR,
//      `kotlinMultiplatform` metadata, and per-target klibs. KMP consumers
//      resolve these from `commonMain`; no XCFramework involved.
//   2. GitHub Releases (this block) — the SKIE-enhanced `Backgrounder.xcframework`
//      zip for pure-Swift consumers, referenced from the root /Package.swift
//      by URL + checksum so `swift package resolve` needs no local Gradle
//      build and no authentication.
//
// GitHub *Releases*, not GitHub *Packages*: Packages requires a PAT even to
// download from public repos (every SPM consumer would need a ~/.netrc),
// and Maven Central can't host the zip either — KMMBridge has no Central
// Portal artifact manager, and Central's staging + sync delay would leave
// the freshly-pushed tag referencing a URL that doesn't resolve yet.
// Release assets are public, immediate, and checksum-pinned by SPM.
//
// `gitHubReleaseArtifacts` uploads `Backgrounder.xcframework.zip` to the GitHub
// Release tagged `v${project.version}`, creating the release if it doesn't
// exist. (`releasString` [sic] is KMMBridge 1.2.x's parameter name; without
// it the release tag would be the bare version, breaking the repo's `vX.Y.Z`
// tag convention.) Publishing is CI-only: the `kmmBridgePublish` umbrella
// task is only registered when `-PENABLE_PUBLISHING=true` is passed, and the
// upload reads the `GITHUB_REPO` / `GITHUB_PUBLISH_TOKEN` Gradle properties —
// .github/workflows/release.yml supplies all three. Local builds skip the
// publish wiring entirely; the `spmDevBuild` task (always registered) is the
// local-dev entry point — see mise task `spm:dev`.
gitHubReleaseArtifacts(releasString = "v${project.version}")

kmmbridge {
    // The XCFramework's Swift module name. Must match the `baseName` set on
    // each framework binary above, or the generated Package.swift references
    // a binary that doesn't exist.
    frameworkName.set("Backgrounder")

    // `swiftToolVersion = "6.0"` because the platform constants `.iOS(.v18)`
    // and `.macOS(.v15)` need PackageDescription 6.0; KMMBridge defaults to
    // 5.3, which can't compile them.
    //
    // Platform floors match `gradle/libs.versions.toml`
    // (ios-deployment-target = 18.0, macos-deployment-target = 15.0). They're
    // spelled "18" / "15" here because KMMBridge emits `.iOS(.v$value)`
    // verbatim — "18.0" would produce the non-existent constant `.v18.0`.
    spm(swiftToolVersion = "6.0") {
        iOS { v("18") }
        macOS { v("15") }
    }
}

// --- Maven Central publishing (CLAUDE.md §9) ---------------------------------
//
// The KMP distribution channel (the Swift/SPM channel is the KMMBridge block
// above): Maven Central via vanniktech's `maven-publish` plugin. One Gradle
// invocation publishes:
//
//   * The Android AAR.
//   * The `kotlinMultiplatform` metadata module (`backgrounder-0.2.0.module`)
//     that ties every target together so KMP consumers can write
//     `implementation("com.happycodelucky.backgrounder:backgrounder:X.Y.Z")` from
//     `commonMain` and have Gradle resolve the right per-target klib.
//   * Per-target artifacts: `backgrounder-iosarm64`, `backgrounder-iossimulatorarm64`,
//     `backgrounder-macosarm64` (klibs), `backgrounder-android` (AAR), and
//     `backgrounder-jvm` (JAR) — one Maven artifact per publication the KMP
//     plugin registers automatically.
//   * Sources / javadoc jars next to each, with detached GPG signatures.
//
// Consumers in another KMP project just add `mavenCentral()` to their
// repositories and depend on the coordinate; no extra setup needed on the
// consumer side.
//
// Credentials: vanniktech reads `mavenCentralUsername`, `mavenCentralPassword`,
// `signingInMemoryKey`, and `signingInMemoryKeyPassword` as Gradle properties.
// Gradle auto-populates those from `ORG_GRADLE_PROJECT_*` env vars in CI. The
// release workflow wires the four `MAVEN_CENTRAL_*` GitHub Actions secrets to
// those env names. Locally these properties are unset and signing is silently
// skipped — fine for `publishToMavenLocal` dry-runs.
mavenPublishing {
    // SonatypeHost.CENTRAL_PORTAL targets the new central.sonatype.com
    // endpoint. Do NOT use SonatypeHost.DEFAULT — that's the legacy
    // s01.oss.sonatype.org OSSRH endpoint, which Sonatype is decommissioning.
    //
    // `automaticRelease = false` is intentional and load-bearing. It controls
    // what `./gradlew :backgrounder:publishToMavenCentral` does:
    //   * `false` — uploads to the Central Portal staging area and stops.
    //     The deployment sits in "validated" state until someone clicks
    //     Publish (or Drop) in the Portal web UI. This is what makes the
    //     release workflow's `dryRun=true` branch an actual dry run.
    //   * `true` — uploads *and* auto-releases on success. Every "dry run"
    //     becomes an irreversible public publish. Do NOT flip this without
    //     understanding the cascade in `.github/workflows/release.yml`.
    //
    // The `publishAndReleaseToMavenCentral` task is unaffected by this flag —
    // it always closes & releases the deployment regardless, and the
    // release workflow uses it on the `dryRun=false` branch.
    publishToMavenCentral(automaticRelease = false)

    // Required by Central — every artifact (jar, aar, klib, module, pom)
    // must carry a detached GPG signature next to it. signAllPublications()
    // applies the signing plugin across every publication the KMP plugin
    // registered (`kotlinMultiplatform`, `android`, `jvm`, `iosArm64`,
    // `iosSimulatorArm64`, `macosArm64`). Central rejects unsigned uploads.
    signAllPublications()

    // The coordinate triple. groupId here intentionally matches the
    // namespace claimed on the Central Portal (`com.happycodelucky`); the
    // root build.gradle.kts wires `group = "com.happycodelucky.backgrounder"`
    // and we mirror that here for the artifact suffix.
    coordinates(
        groupId = "com.happycodelucky.backgrounder",
        artifactId = "backgrounder",
        version = project.version.toString(),
    )

    pom {
        name.set("Backgrounder")
        description.set(
            "Kotlin Multiplatform background-work scheduler for iOS, macOS, Android, and the JVM.",
        )
        url.set("https://github.com/happycodelucky/backgrounder")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("happycodelucky")
                name.set("Paul Bates")
                url.set("https://github.com/happycodelucky")
            }
        }
        scm {
            url.set("https://github.com/happycodelucky/backgrounder")
            connection.set("scm:git:https://github.com/happycodelucky/backgrounder-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/happycodelucky/backgrounder-kmp.git")
        }
    }
}
