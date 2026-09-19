package com.happycodelucky.backgrounder.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives a real Gradle build (Kotlin JVM + this plugin) through TestKit.
 * A JVM project is enough: the scanner reads class files, and the class
 * files a KMP `jvm()` target produces for `commonMain` are the same shape.
 */
class BackgrounderPluginFunctionalTest {
    private val kotlinVersion: String =
        System.getProperty("backgrounder.kotlinVersion") ?: error("backgrounder.kotlinVersion system property not set")

    private fun project(
        constants: String,
        plist: String? = DEFAULT_PLIST,
        extra: String = "",
    ): File {
        val dir = Files.createTempDirectory("backgrounder-plugin-test").toFile()
        dir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories { mavenCentral() }
            }
            rootProject.name = "sample"
            """.trimIndent(),
        )
        val plistLine = if (plist != null) """iosInfoPlist = file("ios/Info.plist")""" else ""
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("org.jetbrains.kotlin.jvm") version "$kotlinVersion"
                id("com.happycodelucky.backgrounder")
            }
            backgrounder {
                $plistLine
                $extra
            }
            """.trimIndent(),
        )
        val src = dir.resolve("src/main/kotlin").apply { mkdirs() }
        src.resolve("BackgroundTaskId.kt").writeText(
            """
            package com.happycodelucky.backgrounder
            @Target(AnnotationTarget.FIELD)
            @Retention(AnnotationRetention.BINARY)
            annotation class BackgroundTaskId
            """.trimIndent(),
        )
        src.resolve("Ids.kt").writeText("import com.happycodelucky.backgrounder.BackgroundTaskId\n\n$constants")
        if (plist != null) {
            dir.resolve("ios").mkdirs()
            dir.resolve("ios/Info.plist").writeText(plist)
        }
        return dir
    }

    private fun runner(
        dir: File,
        vararg args: String,
    ): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(*args, "--configuration-cache", "--stacktrace")

    @Test
    fun collectsIdsAndRewritesPlist() {
        val dir =
            project(
                """
                object AppTasks {
                    @BackgroundTaskId const val TICK = "dev.example.app.background-tick"
                    @BackgroundTaskId const val SYNC = "dev.example.app.sync"
                }
                class Worker { companion object { @BackgroundTaskId const val UPLOAD = "dev.example.app.upload" } }
                """.trimIndent(),
            )
        val result = runner(dir, "updateBackgrounderInfoPlist").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":collectBackgroundTaskIds")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":updateBackgrounderInfoPlist")?.outcome)

        assertEquals(
            "dev.example.app.background-tick\ndev.example.app.sync\ndev.example.app.upload\n",
            dir.resolve("build/backgrounder/task-ids.txt").readText(),
        )
        val plist = dir.resolve("ios/Info.plist").readText()
        assertTrue(plist.contains("<string>dev.example.app.background-tick</string>"), plist)
        assertTrue(plist.contains("<string>dev.example.app.upload</string>"), plist)
        assertTrue(!plist.contains("stale.id"), plist)
        assertTrue(plist.contains("<key>CFBundleName</key>"), "unrelated keys survive")

        // Second run: collect is up to date, plist untouched.
        val again = runner(dir, "updateBackgrounderInfoPlist").build()
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":collectBackgroundTaskIds")?.outcome)
        assertTrue(again.output.contains("no change"), again.output)
    }

    @Test
    fun failsOnDuplicateIds() {
        val dir =
            project(
                """
                object A { @BackgroundTaskId const val X = "dev.example.dup" }
                object B { @BackgroundTaskId const val Y = "dev.example.dup" }
                """.trimIndent(),
            )
        val result = runner(dir, "collectBackgroundTaskIds").buildAndFail()
        assertTrue(result.output.contains("declared more than once"), result.output)
        assertTrue(result.output.contains("A.X") && result.output.contains("B.Y"), result.output)
    }

    @Test
    fun failsOnNonConstAndBlank() {
        val dir =
            project(
                """
                object A {
                    @BackgroundTaskId val NOT_CONST = "dev.example.a"
                    @BackgroundTaskId const val BLANK = " "
                }
                """.trimIndent(),
            )
        val result = runner(dir, "collectBackgroundTaskIds").buildAndFail()
        assertTrue(result.output.contains("requires a `const val String`"), result.output)
        assertTrue(result.output.contains("is blank"), result.output)
    }

    @Test
    fun warnsOnNonReverseDnsButSucceeds() {
        val dir = project("""object A { @BackgroundTaskId const val X = "sync" }""")
        val result = runner(dir, "collectBackgroundTaskIds").build()
        assertTrue(result.output.contains("does not follow the reverse-DNS convention"), result.output)
    }

    @Test
    fun plistTaskSkippedWhenNotConfigured() {
        val dir = project("""object A { @BackgroundTaskId const val X = "dev.example.x" }""", plist = null)
        val result = runner(dir, "updateBackgrounderInfoPlist").build()
        assertEquals(TaskOutcome.SKIPPED, result.task(":updateBackgrounderInfoPlist")?.outcome)
    }

    private companion object {
        val DEFAULT_PLIST: String =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\">\n" +
                "<dict>\n" +
                "\t<key>CFBundleName</key>\n" +
                "\t<string>Sample</string>\n" +
                "\t<key>BGTaskSchedulerPermittedIdentifiers</key>\n" +
                "\t<array>\n" +
                "\t\t<string>stale.id</string>\n" +
                "\t</array>\n" +
                "</dict>\n" +
                "</plist>\n"
    }
}
