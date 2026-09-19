package com.happycodelucky.backgrounder

import com.russhwolf.settings.Settings
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The persistent set of task ids that opted into the cold-launch sweep.
 *
 * Backed by `multiplatform-settings` (`NSUserDefaults` on Apple,
 * `SharedPreferences` on Android). Tiny, single-key — values are joined with
 * a separator to fit `Settings`' string-typed model.
 *
 * Lifecycle:
 * - [add] called by `Scheduler.schedule` when `request.ephemeral == true`.
 * - [snapshot] read by the platform-specific cold-launch sweep.
 * - [clear] called once the sweep finishes.
 * - [remove] called by `Scheduler.cancel` to keep the mirror tight.
 */
internal class EphemeralRegistry(
    private val settings: Settings,
) {
    // Guards every read-modify-write on the underlying single-key store. Without
    // this, two concurrent `add` calls can both read the pre-add snapshot and
    // race on the write — the loser's id is lost. Critical sections are short
    // and never call into suspend code, so a non-suspending lock is correct.
    // MUST NOT call suspend functions inside this block.
    private val lock = SynchronizedObject()

    fun add(taskId: String) {
        synchronized(lock) {
            val current = snapshotInternal().toMutableSet()
            if (current.add(taskId)) write(current)
        }
    }

    fun remove(taskId: String) {
        synchronized(lock) {
            val current = snapshotInternal().toMutableSet()
            if (current.remove(taskId)) write(current)
        }
    }

    fun snapshot(): Set<String> = synchronized(lock) { snapshotInternal() }

    fun clear() {
        synchronized(lock) { settings.remove(KEY) }
    }

    private fun snapshotInternal(): Set<String> {
        val raw = settings.getStringOrNull(KEY) ?: return emptySet()
        if (raw.isEmpty()) return emptySet()
        return raw
            .splitToSequence(SEPARATOR)
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun write(ids: Set<String>) {
        if (ids.isEmpty()) {
            settings.remove(KEY)
        } else {
            settings.putString(KEY, ids.joinToString(SEPARATOR.toString()))
        }
    }

    companion object {
        /**
         * ASCII unit separator (U+001F). Cannot occur inside a task id
         * (`requireValidTaskId` rejects control characters), so safe as a delimiter without escaping.
         * Defined via numeric conversion so the source contains no invisible characters.
         */
        internal val SEPARATOR: Char = 0x1F.toChar()
        internal const val KEY: String = "backgrounder.ephemeral_task_ids"
    }
}
