package com.happycodelucky.backgrounder

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A request to schedule background work, identified by a stable task id.
 *
 * Sealed: v1 supports [OneTime] and [Periodic]. Both share an [ephemeral] flag
 * for the cold-launch sweep — see `BackgroundTaskManager.attachTo` /
 * `BackgroundTaskManager.registerHandlers`.
 */
@Serializable
public sealed interface WorkRequest {
    public val taskId: String
    public val constraints: WorkConstraints
    public val input: WorkInput

    /**
     * If `true`, this request is cleared at app cold-start before any worker
     * dispatches. Schedule it again from app code at a known-safe init point.
     *
     * Use this when the worker depends on app state that is initialized after
     * `Application.onCreate` / `application(_:didFinishLaunchingWithOptions:)`.
     */
    public val ephemeral: Boolean

    /** Run once; survives process death and reboot unless [ephemeral]. */
    @Serializable
    public data class OneTime(
        override val taskId: String,
        override val constraints: WorkConstraints = WorkConstraints(),
        override val input: WorkInput = WorkInput.empty(),
        override val ephemeral: Boolean = false,
        /** Minimum delay before the platform dispatches this request. May be [Duration.ZERO]. */
        val initialDelay: Duration = Duration.ZERO,
        /** Retry policy applied when the worker returns [WorkResult.Retry]. */
        val backoff: BackoffPolicy = BackoffPolicy.exponential(30.seconds),
        /** Hint to the scheduler about urgency; see [ExecutionHint.Expedited] for iOS/Android specifics. */
        val executionHint: ExecutionHint = ExecutionHint.Standard,
    ) : WorkRequest {
        init {
            requireValidTaskId(taskId)
            require(initialDelay >= Duration.ZERO) { "initialDelay must be >= 0" }
        }
    }

    /**
     * Repeats indefinitely until cancelled. Android floor is 15 minutes
     * (validated by the scheduler at enqueue time); iOS / macOS have no system
     * floor but we recommend ≥ 15 minutes for parity.
     */
    @Serializable
    public data class Periodic(
        override val taskId: String,
        override val constraints: WorkConstraints = WorkConstraints(),
        override val input: WorkInput = WorkInput.empty(),
        override val ephemeral: Boolean = false,
        /** How often the scheduler should repeat this task. Must be `>= 15 minutes` (Android floor). */
        val interval: Duration,
        /**
         * Execution flex window at the end of each [interval]. If non-null, the platform may
         * fire the task anywhere in the final [flexWindow] of the period.
         * Maps to [androidx.work.PeriodicWorkRequest] flex interval and macOS `tolerance`.
         * iOS does not use this value (BGProcessingTaskRequest has no flex window).
         */
        val flexWindow: Duration? = null,
    ) : WorkRequest {
        init {
            requireValidTaskId(taskId)
            require(interval >= MIN_RECOMMENDED_INTERVAL) {
                "interval must be >= $MIN_RECOMMENDED_INTERVAL (Android floor); was $interval"
            }
            if (flexWindow != null) {
                require(flexWindow > Duration.ZERO && flexWindow < interval) {
                    "flexWindow must be > 0 and < interval; was $flexWindow with interval $interval"
                }
            }
        }
    }

    public companion object {
        /**
         * Minimum interval recommended for [Periodic] requests. Matches Android
         * `PeriodicWorkRequest`'s hard 15-minute floor; used as a recommendation
         * on iOS / macOS where no system floor exists.
         */
        public val MIN_RECOMMENDED_INTERVAL: Duration = 15.minutes
    }
}
