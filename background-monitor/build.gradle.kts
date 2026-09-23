@file:Suppress("UnstableApiUsage")

/*
 * Backgrounder — :background-monitor module.
 *
 * Optional sibling to :backgrounder that gives consumers a higher-level
 * vocabulary on top of `Backgrounder.events()` and the inspector APIs:
 *
 *   * `Monitor` — an attached observer that receives every `MonitorEvent`
 *     and can correlate them against snapshot polls. Multiple monitors can
 *     attach to one Backgrounder; each runs on its own collector coroutine.
 *   * `SnapshotPoller` — convenience wrapper that re-queries `scheduled()`
 *     / `diagnostics()` on an interval, useful for inspector UIs that
 *     refresh on a fixed cadence.
 *
 * No platform-specific code here — everything lives in commonMain on top of
 * the cross-platform surface :backgrounder already exposes. The module is
 * pure-Kotlin and `applyDefaultHierarchyTemplate()`-shaped for the same
 * Tier-1 targets as the core.
 *
 * Headless like the core (CLAUDE.md §1, §7) — no UI dependencies. Consumers
 * render the monitor's output in their own SwiftUI / Compose / web UI.
 */

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // SKIE intentionally omitted: this module publishes only klibs + the
    // Android AAR via Maven Central. The Apple Swift-interop refinement
    // (SKIE) is applied in :backgrounder, where the framework binaries are
    // produced and aggregated into Backgrounder.xcframework. KMP consumers
    // pick the monitor module up via Gradle; pure-Swift consumers stay on
    // the core's XCFramework — which already includes :backgrounder's
    // public types that the monitor's API surface references. If pure-Swift
    // consumption of the monitor surface is ever needed, attach a second
    // target.binaries.framework here and re-enable SKIE.
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("apple") {
                withIos()
                withMacos()
            }
        }
    }

    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    // Architecture-neutral JVM (desktop / server) — matches the core's target
    // roster so a JVM consumer of :backgrounder can layer the monitor on top.
    jvm()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    android {
        namespace = "com.happycodelucky.backgrounder.monitor"
        compileSdk =
            libs.versions.android.compile.sdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.min.sdk
                .get()
                .toInt()

        withHostTestBuilder { /* enables androidUnitTest */ }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
    }

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
            // The whole point of this module is to layer over the core's
            // public API. `api` so that consumers depending on
            // :background-monitor automatically see :backgrounder's types
            // (MonitorEvent, ScheduledTask, PlatformDiagnostics, …) without
            // a second `implementation(...)` line.
            api(project(":backgrounder"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kermit)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }

        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}

// Maven Central — same Sonatype Central Portal target as :backgrounder.
// Publishes alongside the core under its own artifactId so consumers can
// opt in: `implementation("com.happycodelucky.backgrounder:background-monitor:X.Y.Z")`.
mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()

    coordinates(
        groupId = "com.happycodelucky.backgrounder",
        artifactId = "background-monitor",
        version = project.version.toString(),
    )

    pom {
        name.set("Backgrounder Monitor")
        description.set(
            "Optional monitor / inspector helpers for the Backgrounder KMP library. " +
                "Layers `Monitor` and `SnapshotPoller` on top of `Backgrounder.events()` and the inspector APIs.",
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
