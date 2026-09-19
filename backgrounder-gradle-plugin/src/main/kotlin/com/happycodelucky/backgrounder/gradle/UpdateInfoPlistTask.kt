package com.happycodelucky.backgrounder.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * Rewrites `BGTaskSchedulerPermittedIdentifiers` in [infoPlist] from [manifest].
 *
 * Untracked on purpose: the plist is both read and written, and it lives in
 * the app repo outside Gradle's build directory. Gradle can't fingerprint an
 * in-place edit sensibly, and the rewrite is idempotent and cheap, so it
 * simply runs every time it's requested.
 */
@UntrackedTask(because = "edits the iOS Info.plist in place; idempotent and cheap")
public abstract class UpdateInfoPlistTask : DefaultTask() {
    public companion object {
        /** Mirrors `DEFAULT_TICK_SUFFIX` in `:backgrounder`'s `Backgrounder.ios.kt`; keep in sync. */
        public const val DEFAULT_TICK_SUFFIX: String = ".backgrounder-tick"
    }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val manifest: RegularFileProperty

    @get:Internal
    public abstract val infoPlist: RegularFileProperty

    /**
     * The library's default iOS tick identifier, derived from
     * `backgrounder.iosBundleIdentifier`. Added to the array when present.
     */
    @get:Input
    @get:Optional
    public abstract val defaultTickIdentifier: Property<String>

    @TaskAction
    public fun update() {
        val plistFile = infoPlist.get().asFile
        if (!plistFile.isFile) {
            throw GradleException("Backgrounder: iosInfoPlist '${plistFile.path}' does not exist.")
        }
        val ids =
            buildSet {
                addAll(
                    manifest
                        .get()
                        .asFile
                        .readLines()
                        .filter { it.isNotEmpty() },
                )
                defaultTickIdentifier.orNull?.let { add(it) }
            }
        val before = plistFile.readText()
        val after = InfoPlistRewriter.rewrite(before, ids)
        if (after == before) {
            logger.lifecycle("Backgrounder: ${plistFile.name} already lists ${ids.size} permitted identifier(s); no change.")
            return
        }
        plistFile.writeText(after)
        logger.lifecycle("Backgrounder: wrote ${ids.size} permitted identifier(s) to ${plistFile.path}")
    }
}
