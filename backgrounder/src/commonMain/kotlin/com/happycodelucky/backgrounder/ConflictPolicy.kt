package com.happycodelucky.backgrounder

/**
 * What [Scheduler.schedule] does when a request with the same task id is
 * already pending.
 *
 * `Append` (Android-only chained work) is v2; v1 has the two cross-platform
 * cases.
 */
public enum class ConflictPolicy {
    /** Cancel the pending request and enqueue this one. Maps to Android `REPLACE`. */
    Replace,

    /** Keep the existing pending request; ignore this one. Maps to Android `KEEP`. */
    Keep,
}
