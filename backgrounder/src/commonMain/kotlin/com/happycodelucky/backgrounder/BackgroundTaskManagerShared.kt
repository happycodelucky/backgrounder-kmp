package com.happycodelucky.backgrounder

import kotlin.experimental.ExperimentalObjCName
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlin.native.ObjCName

/**
 * The process-wide [BackgroundTaskManager].
 *
 * ```kotlin
 * BackgroundTaskManager.shared.register(SyncWorker.ID) { SyncWorker(repo = graph.repository) }
 * BackgroundTaskManager.shared.start()
 * ```
 *
 * - **Android**: populated before `Application.onCreate` by the
 *   `androidx.startup` initializer the library registers in its manifest.
 *   If your app removed the `InitializationProvider`, call
 *   `BackgroundTaskManager.configure(application)` first; accessing `shared` before
 *   either has run throws [IllegalStateException] with that instruction.
 * - **iOS / macOS / JVM**: created lazily on first access. Call
 *   `BackgroundTaskManager.create(...)` first only if you need a
 *   [BackgrounderEventListener] or, on iOS, a custom tick identifier.
 *
 * Swift sees this as `BackgroundTaskManager.shared` through a wrapper bundled in the
 * framework. `@HiddenFromObjC` here because Kotlin/Native already exports
 * every companion object with a `shared` accessor of its own, so a Kotlin
 * property of the same name would collide; the wrapper calls [sharedInstance].
 *
 * `@OptIn(ExperimentalObjCRefinement::class)`: standard SKIE-recognised
 * refinement annotation; stable in practice (CLAUDE.md §8).
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
public val BackgroundTaskManager.Companion.shared: BackgroundTaskManager
    get() = SharedBackgroundTaskManager.get()

/**
 * Objective-C-visible accessor for [shared]. Swift callers use
 * `BackgroundTaskManager.shared`, a bundled wrapper over this function; Kotlin callers
 * use the [shared] property. Exists only because the property name collides
 * with Kotlin/Native's generated companion accessor.
 *
 * `@OptIn(ExperimentalObjCName::class)`: Swift-rename annotation for boundary
 * refinement. Stable in practice and required by SKIE (CLAUDE.md §8).
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "sharedInstance")
public fun BackgroundTaskManager.Companion.sharedInstance(): BackgroundTaskManager = SharedBackgroundTaskManager.get()
