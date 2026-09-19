package com.happycodelucky.backgrounder

/**
 * Marks a `const val String` as an identifier that must appear in the iOS
 * app's `BGTaskSchedulerPermittedIdentifiers` `Info.plist` array.
 *
 * **iOS-only concern.** Android and macOS need nothing like it, and it says
 * nothing about how the work runs. Two kinds of id belong in that array:
 * the tick identifier passed to `Backgrounder.create(tickIdentifier:)`, and
 * every id you may schedule as a [WorkRequest.OneTime]. Periodic ids and
 * `runNow` ids never reach `BGTaskScheduler` and don't need it — annotating
 * them anyway is harmless (a surplus plist entry costs nothing), whereas a
 * missing entry means iOS silently never fires that task.
 *
 * ```kotlin
 * class SyncWorker(...) : BackgroundWorker {
 *     companion object {
 *         @BGTaskSchedulerPermittedIdentifier const val SYNC = "dev.example.app.sync"
 *     }
 * }
 * ```
 *
 * The Backgrounder Gradle plugin (`com.happycodelucky.backgrounder`) scans the
 * compiled JVM classes of the shared module for annotated constants, validates
 * them, and rewrites the `BGTaskSchedulerPermittedIdentifiers` array in the
 * iOS app's `Info.plist` from that list. See docs/recipes/ios-permitted-identifiers.md.
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
public annotation class BGTaskSchedulerPermittedIdentifier
