/*
 * Backgrounder — KMP background work scheduling library.
 *
 * /backgrounder is the headless KMP core module; /background-monitor is the
 * optional instrumentation layer over its public API. Directory names match
 * the Gradle module names and the published Maven artifact ids
 * (`com.happycodelucky.backgrounder:backgrounder` / `:background-monitor`),
 * mirroring the convention used by Ktor, kotlinx, and our sibling
 * `com.happycodelucky.reachable:reachable` library — the redundant ":shared"
 * coordinate would only add noise once published.
 *
 * Platform apps (androidApp, iOSApp, macOSApp) live outside this Gradle build.
 * KMP consumers resolve via Maven Central; the in-repo sample apps consume the
 * debug XCFramework through the root Package.swift (local SPM). Remote SPM /
 * KMMBridge distribution is future work (CLAUDE.md §9).
 */

@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Project-level repos win; subprojects must not redeclare.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "backgrounder"

include(":backgrounder")
include(":background-monitor")
include(":backgrounder-gradle-plugin")
