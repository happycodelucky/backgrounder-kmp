package com.happycodelucky.backgrounder

/**
 * The result of [Scheduler.schedule].
 *
 * Sealed (not nullable + error code) so SKIE generates an exhaustive Swift
 * `enum` (CLAUDE.md §8 rule 2).
 */
public sealed interface ScheduleOutcome {
    /** The request was accepted and submitted to the platform scheduler. */
    public data object Scheduled : ScheduleOutcome

    /**
     * The request was rejected without being submitted to the platform
     * scheduler. The [reason] is human-readable and meant for logs / dialogs.
     *
     * Common reasons:
     * - iOS task identifier missing from `BGTaskSchedulerPermittedIdentifiers`.
     * - Periodic interval below the Android 15-minute floor.
     * - `BackgroundTaskManager.registerHandlers` not yet called on iOS / macOS.
     */
    public data class Rejected(
        val reason: String,
    ) : ScheduleOutcome
}
