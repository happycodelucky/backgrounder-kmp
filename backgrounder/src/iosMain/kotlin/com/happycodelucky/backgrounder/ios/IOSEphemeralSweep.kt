package com.happycodelucky.backgrounder.ios

import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.EphemeralRegistry
import platform.BackgroundTasks.BGTaskScheduler

/**
 * Cancels every iOS-side ephemeral request before any handler is registered.
 *
 * Run as the *first* thing inside `Backgrounder.registerHandlers()`. Because
 * iOS dispatches a registered handler only after `register(...)` is called
 * for that identifier — and that happens *after* this sweep — no ephemeral
 * handler can ever fire before the sweep completes (stronger guarantee than
 * Android's same-named operation).
 */
internal class IOSEphemeralSweep(
    private val ephemeral: EphemeralRegistry,
    private val state: IOSStateStore,
) {
    private val log = Logger.withTag("Backgrounder/iOS/EphemeralSweep")

    /** Snapshot at construction so a pre-start ephemeral schedule survives the sweep. */
    private val ids: Set<String> = ephemeral.snapshot()

    fun run() {
        if (ids.isEmpty()) {
            log.d { "no ephemeral entries to sweep" }
            return
        }
        log.i { "sweeping ${ids.size} ephemeral request(s)" }
        ids.forEach { id ->
            BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(id)
            state.clear(id)
        }
        ids.forEach(ephemeral::remove)
    }
}
