package com.happycodelucky.backgrounder.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers two tasks:
 *
 *  - `collectBackgroundTaskIds` — scans the compile task's class output for
 *    `@BGTaskSchedulerPermittedIdentifier const val` fields and writes the manifest.
 *  - `updateBackgrounderInfoPlist` — rewrites the plist array from the
 *    manifest. Skipped when `backgrounder.iosInfoPlist` isn't set.
 *
 * The compile task is resolved by name (see [BackgrounderExtension.scanCompileTask])
 * so this plugin never links against the Kotlin Gradle plugin's classes.
 */
public class BackgrounderPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("backgrounder", BackgrounderExtension::class.java)
        ext.scanCompileTask.convention(project.provider { defaultScanTask(project) })
        ext.manifest.convention(project.layout.buildDirectory.file("backgrounder/task-ids.txt"))
        ext.warnOnNonReverseDns.convention(true)

        val compileTask = ext.scanCompileTask.map { name -> project.tasks.named(name) }

        val collect =
            project.tasks.register("collectBackgroundTaskIds", CollectBackgroundTaskIdsTask::class.java) { task ->
                task.group = GROUP
                task.description = "Collects @BGTaskSchedulerPermittedIdentifier constants from compiled classes into a manifest."
                task.dependsOn(compileTask)
                task.classDirs.from(compileTask.flatMap { provider -> provider.map { it.outputs.files } })
                task.manifest.set(ext.manifest)
                task.warnOnNonReverseDns.set(ext.warnOnNonReverseDns)
            }

        project.tasks.register("updateBackgrounderInfoPlist", UpdateInfoPlistTask::class.java) { task ->
            task.group = GROUP
            task.description = "Rewrites BGTaskSchedulerPermittedIdentifiers in the iOS Info.plist from the manifest."
            task.manifest.set(collect.flatMap { it.manifest })
            task.infoPlist.set(ext.iosInfoPlist)
            task.onlyIf("backgrounder.iosInfoPlist is not configured") { t ->
                (t as UpdateInfoPlistTask).infoPlist.isPresent
            }
        }
    }

    private fun defaultScanTask(project: Project): String {
        val names = project.tasks.names
        return CANDIDATE_COMPILE_TASKS.firstOrNull { it in names }
            ?: throw IllegalStateException(
                "Backgrounder: none of ${CANDIDATE_COMPILE_TASKS.joinToString()} exist in project '${project.path}'. " +
                    "Apply a Kotlin plugin, or set backgrounder.scanCompileTask explicitly.",
            )
    }

    private companion object {
        const val GROUP = "backgrounder"

        /** Order matters: KMP jvm target first, then the KMP Android target, then plain JVM. */
        val CANDIDATE_COMPILE_TASKS = listOf("compileKotlinJvm", "compileAndroidMain", "compileKotlin")
    }
}
