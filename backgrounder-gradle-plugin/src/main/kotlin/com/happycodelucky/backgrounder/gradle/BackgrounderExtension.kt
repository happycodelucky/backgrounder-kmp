package com.happycodelucky.backgrounder.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * `backgrounder { }` block.
 *
 * ```kotlin
 * backgrounder {
 *     iosInfoPlist = file("../iOSApp/App/Info.plist")
 * }
 * ```
 */
public abstract class BackgrounderExtension {
    /**
     * The iOS app's `Info.plist`. When set, `updateBackgrounderInfoPlist`
     * rewrites its `BGTaskSchedulerPermittedIdentifiers` array from the
     * collected ids. When unset, only the manifest is produced.
     */
    public abstract val iosInfoPlist: RegularFileProperty

    /**
     * Name of the compile task whose class output is scanned. Defaults to
     * the first of `compileKotlinJvm`, `compileAndroidMain`, `compileKotlin`
     * that exists in the project, in that order.
     */
    public abstract val scanCompileTask: Property<String>

    /**
     * Where the collected ids are written, one per line, sorted. Defaults to
     * `build/backgrounder/task-ids.txt`.
     */
    public abstract val manifest: RegularFileProperty

    /**
     * Log a warning for ids that don't follow the reverse-DNS convention
     * (`com.example.app.sync`). Never an error — the shape is a convention,
     * not a rule. Defaults to `true`.
     */
    public abstract val warnOnNonReverseDns: Property<Boolean>
}
