/*
 * Backgrounder — :backgrounder-gradle-plugin.
 *
 * Build-time companion to the library. Scans the shared module's compiled
 * classes for `@BGTaskSchedulerPermittedIdentifier const val` declarations, validates them, and
 * rewrites `BGTaskSchedulerPermittedIdentifiers` in the iOS app's Info.plist
 * so the plist and the code can never disagree.
 *
 * Deliberately has no compile dependency on the Kotlin Gradle plugin: it
 * locates the compile task by name (`compileKotlinJvm` / `compileAndroidMain`
 * / `compileKotlin`) and reads that task's outputs. That keeps the plugin
 * loadable next to whatever KGP version the consumer runs, and keeps the
 * functional tests free of classloader games.
 *
 * Runs inside the Gradle daemon, so the library's ARM-only / KMP rules don't
 * apply here — this is plain JVM code on Gradle's classpath.
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()
    compilerOptions {
        // Gradle 9.4 embeds Kotlin 2.3.0 but runs plugin code at language /
        // API level 2.2 (docs.gradle.org/current/userguide/compatibility.html).
        // Compiling against 2.2 keeps the plugin loadable on every Gradle 9.x
        // daemon, whatever Kotlin the consumer's build script uses. This is
        // the documented exception to CLAUDE.md §3's "current stable" rule.
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        // Gradle 9 runs on JDK 17+. The library targets 21 (CLAUDE.md §2), but
        // a plugin compiled for 21 would refuse to load on a 17 daemon.
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.asm)
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("backgrounder") {
            id = "com.happycodelucky.backgrounder"
            implementationClass = "com.happycodelucky.backgrounder.gradle.BackgrounderPlugin"
            displayName = "Backgrounder"
            description =
                "Collects @BGTaskSchedulerPermittedIdentifier constants from the shared module and keeps the iOS " +
                "BGTaskSchedulerPermittedIdentifiers Info.plist array in sync with them."
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // The functional test spins up a real Gradle build that applies the Kotlin
    // JVM plugin; it needs to know which version this repo pins.
    systemProperty("backgrounder.kotlinVersion", libs.versions.kotlin.get())
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()

    coordinates(
        groupId = "com.happycodelucky.backgrounder",
        artifactId = "backgrounder-gradle-plugin",
        version = project.version.toString(),
    )

    pom {
        name.set("Backgrounder Gradle Plugin")
        description.set(
            "Gradle plugin for the Backgrounder KMP library. Collects @BGTaskSchedulerPermittedIdentifier constants and " +
                "rewrites BGTaskSchedulerPermittedIdentifiers in the iOS Info.plist.",
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
            connection.set("scm:git:https://github.com/happycodelucky/backgrounder.git")
            developerConnection.set("scm:git:ssh://git@github.com/happycodelucky/backgrounder.git")
        }
    }
}
