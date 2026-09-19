package com.happycodelucky.backgrounder.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Scans [classDirs] for `@BackgroundTaskId` constants and writes them to
 * [manifest], one id per line, sorted.
 *
 * Fails the build on: a non-`const` annotated field, an id that breaks the
 * runtime rules, or two constants carrying the same id. Warns (only) on ids
 * that don't look reverse-DNS.
 */
@CacheableTask
public abstract class CollectBackgroundTaskIdsTask : DefaultTask() {
    /** Class output of the scanned compile task. Non-class files are ignored. */
    @get:Classpath
    public abstract val classDirs: ConfigurableFileCollection

    @get:Input
    public abstract val warnOnNonReverseDns: Property<Boolean>

    @get:OutputFile
    public abstract val manifest: RegularFileProperty

    @TaskAction
    public fun collect() {
        val result = TaskIdScanner.scan(classDirs.files)
        val problems = mutableListOf<String>()

        result.nonConstant.forEach { problems += "${it.location}: @BackgroundTaskId requires a `const val String`" }
        result.ids.forEach { found ->
            TaskIdRules.problem(found.id)?.let { problems += "${found.location}: id '${found.id}' $it" }
        }
        result.ids
            .groupBy { it.id }
            .filterValues { it.size > 1 }
            .forEach { (id, owners) ->
                problems += "id '$id' is declared more than once: ${owners.joinToString { it.location }}"
            }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Backgrounder task id validation failed:\n" + problems.joinToString("\n") { "  - $it" },
            )
        }

        if (warnOnNonReverseDns.get()) {
            result.ids.filterNot { TaskIdRules.looksReverseDns(it.id) }.forEach {
                logger.warn(
                    "Backgrounder: task id '${it.id}' (${it.location}) does not follow the reverse-DNS convention. " +
                        "Set backgrounder.warnOnNonReverseDns = false to silence this.",
                )
            }
        }

        val ids = result.ids.map { it.id }.sorted()
        val out = manifest.get().asFile
        out.parentFile.mkdirs()
        out.writeText(ids.joinToString(separator = "\n", postfix = if (ids.isEmpty()) "" else "\n"))
        logger.lifecycle("Backgrounder: collected ${ids.size} task id(s) into ${out.path}")
    }
}
