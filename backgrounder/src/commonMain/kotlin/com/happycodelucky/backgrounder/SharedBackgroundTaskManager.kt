package com.happycodelucky.backgrounder

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The process-wide [BackgroundTaskManager] slot behind `BackgroundTaskManager.shared`.
 *
 * There is at most one live [BackgroundTaskManager] per process. The platforms force
 * this: `WorkManager` and `BGTaskScheduler.shared` are process singletons,
 * and a second instance registering the same ids would double-register
 * against them. Making the rule explicit lets every platform bridge find
 * "the instance" without instance-keyed lookups.
 *
 * Population differs per platform:
 *  - **Android** — the `androidx.startup` [BackgrounderInitializer] calls
 *    `BackgroundTaskManager.configure(application)` before `Application.onCreate`;
 *    apps that removed the startup provider call `configure` themselves.
 *  - **iOS / macOS / JVM** — created lazily on first access via
 *    [createDefaultBackgrounder]; explicit `BackgroundTaskManager.create(...)`
 *    beforehand wins if the app needs a listener or a custom tick identifier.
 *
 * [BackgroundTaskManager.shutdown] releases the slot so a fresh instance can be built,
 * which is what tests rely on.
 */
internal object SharedBackgroundTaskManager {
    // MUST NOT call suspend functions inside this block — see CLAUDE.md §3.
    // Reentrant on purpose: get() → createDefaultBackgrounder() → a platform
    // builder → install() all happen under this lock.
    private val lock = SynchronizedObject()
    private val current = atomic<BackgroundTaskManager?>(null)

    /** The live instance, creating the platform default if none exists. */
    fun get(): BackgroundTaskManager =
        current.value ?: synchronized(lock) {
            current.value ?: createDefaultBackgrounder().also { created ->
                // Builders install themselves; a builder that forgot is a library bug.
                check(current.value === created) {
                    "Backgrounder: the platform default builder did not install its instance as shared"
                }
            }
        }

    /** The live instance if one exists, without creating one. */
    fun peek(): BackgroundTaskManager? = current.value

    /**
     * Claim the slot for [backgrounder].
     *
     * @throws IllegalStateException if another instance is already live.
     */
    fun install(backgrounder: BackgroundTaskManager): Unit =
        synchronized(lock) {
            check(current.compareAndSet(expect = null, update = backgrounder)) {
                "BackgroundTaskManager is already configured for this process; only one instance may be live at a time. " +
                    "Use BackgroundTaskManager.shared, or call shutdown() on the existing instance before creating another."
            }
        }

    /** Release the slot if [backgrounder] holds it. No-op otherwise. */
    fun release(backgrounder: BackgroundTaskManager) {
        synchronized(lock) {
            current.compareAndSet(expect = backgrounder, update = null)
        }
    }

    /** Test seam: drop whatever is installed without running its shutdown. */
    fun resetForTests() {
        synchronized(lock) {
            current.value = null
        }
    }
}

/**
 * Build the platform's zero-configuration instance and install it as shared.
 * Android has no zero-configuration path (it needs an `Application`) and
 * throws with instructions instead.
 */
internal expect fun createDefaultBackgrounder(): BackgroundTaskManager
