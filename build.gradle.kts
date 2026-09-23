/*
 * Backgrounder — root build script.
 *
 * Plugins are declared here with `apply false`; they're applied in :backgrounder.
 * This keeps `gradle/libs.versions.toml` as the single source of truth for
 * versions (CLAUDE.md §10).
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.maven.publish) apply false
    // KSP is not used in v1 (no Koin Annotations, no codegen). Add when needed.

    // Dokka v2: Kotlin API doc generator. Produces HTML for the public API of
    // every source set. The HTML is copied into docs/api/ for mkdocs to bundle.
    alias(libs.plugins.dokka)
}

allprojects {
    group = "com.happycodelucky.backgrounder"
    // The in-tree version carries `-SNAPSHOT` and a `0` patch slot. Humans bump
    // major/minor here and commit the change; the patch slot stays `0`. CI
    // overrides this at build time via `-Pversion=...` to stamp ephemeral
    // patches (run numbers for CI builds, exact `vX.Y.Z` for releases) without
    // ever committing the override back.
    version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")
}

subprojects {
    // ktlint wires onto whichever Kotlin plugin is present. CLAUDE.md §3:
    // "ktlint + detekt must pass."
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }

    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set(libs.versions.ktlint.get())
            android.set(false)
            outputToConsole.set(true)
            ignoreFailures.set(false)
            filter {
                exclude { element -> element.file.path.contains("/build/generated/") }
                exclude("**/build/**")
                exclude("**/generated/**")
            }
        }

        tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
            exclude { element -> element.file.path.contains("/build/generated/") }
        }
    }
}

// The root project is named `backgrounder`, so with the shared group above its
// coordinates collide with :backgrounder's. Gradle then resolves
// `dokka(project(":backgrounder"))` back onto the root itself and the aggregate
// comes out empty. The root is never published, so a distinct group is free.
group = "com.happycodelucky.backgrounder.build"

// Aggregate the published modules' Dokka HTML into docs/api/.
dokka {
    moduleName.set("Backgrounder")
}

dependencies {
    // Aggregate Dokka HTML into the root build (Dokka v2 pattern).
    dokka(project(":backgrounder"))
    dokka(project(":background-monitor"))
}

/**
 * Copies Dokka v2 HTML output into docs/api/, where mkdocs picks it up.
 *
 * The aggregated HTML lives at build/dokka/html after dokkaGeneratePublicationHtml.
 * mkdocs looks at docs/api/ when it builds the site; CI runs Dokka before mkdocs.
 * `Sync`, not `Copy`: docs/api/ is fully generated, so stale pages from renamed or
 * removed modules must be purged rather than shipped.
 */
tasks.register<Sync>("copyDokkaToDocs") {
    group = "documentation"
    description = "Copies aggregated Dokka HTML into docs/api/ for mkdocs."

    dependsOn("dokkaGeneratePublicationHtml")
    from(layout.buildDirectory.dir("dokka/html"))
    into(layout.projectDirectory.dir("docs/api"))
}
