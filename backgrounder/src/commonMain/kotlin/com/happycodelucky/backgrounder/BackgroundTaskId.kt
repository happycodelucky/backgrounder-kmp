package com.happycodelucky.backgrounder

/**
 * Marks a `const val String` as a Backgrounder task id so build tooling can
 * collect it.
 *
 * ```kotlin
 * class SyncWorker(...) : BackgroundWorker {
 *     companion object {
 *         @BackgroundTaskId const val SYNC = "dev.example.app.sync"
 *     }
 * }
 * ```
 *
 * The Backgrounder Gradle plugin (`com.happycodelucky.backgrounder`) scans the
 * compiled JVM classes of the shared module for annotated constants, validates
 * them, and rewrites the `BGTaskSchedulerPermittedIdentifiers` array in the
 * iOS app's `Info.plist` from that list. Annotate the iOS tick identifier the
 * same way — it is just another id that must be in the plist.
 *
 * Constraints, all enforced by the plugin at build time:
 *
 *  - The declaration must be a `const val` of type `String`. Kotlin folds
 *    `const` initializers into the class file where the scanner can read
 *    them; a plain `val` has no compile-time value and fails the build with a
 *    message saying so. `const val` is legal at top level, in an `object`,
 *    or in a `companion object`.
 *  - The value must satisfy the runtime rules in `requireValidTaskId`
 *    (non-blank, no surrounding whitespace, no control characters).
 *  - No two annotated constants may carry the same value.
 *
 * `@Target(FIELD)` so the annotation lands on the JVM static field the scanner
 * inspects, without callers writing `@field:`. `BINARY` retention: present in
 * class files, never needed at runtime.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class BackgroundTaskId
