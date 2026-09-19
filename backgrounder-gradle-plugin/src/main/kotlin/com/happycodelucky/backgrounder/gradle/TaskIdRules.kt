package com.happycodelucky.backgrounder.gradle

/**
 * Build-time mirror of the library's `requireValidTaskId`. The library's
 * copy is `internal` to `:backgrounder` and this plugin runs inside Gradle,
 * so the rules are restated here. Keep the two in sync.
 */
internal object TaskIdRules {
    /** Returns a reason the id is invalid, or `null` when it's acceptable. */
    fun problem(id: String): String? =
        when {
            id.isBlank() -> "is blank"
            id.trim() != id -> "has leading or trailing whitespace"
            id.any { it.isISOControl() } -> "contains control characters"
            else -> null
        }

    private val reverseDns = Regex("""^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)+$""")

    /** The documented convention — `com.example.app.sync`. A warning, never an error. */
    fun looksReverseDns(id: String): Boolean = reverseDns.matches(id)
}
