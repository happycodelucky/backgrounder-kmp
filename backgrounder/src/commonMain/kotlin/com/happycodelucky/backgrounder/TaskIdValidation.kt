package com.happycodelucky.backgrounder

/**
 * The rules every task id must satisfy: non-blank, no leading or trailing
 * whitespace, no control characters.
 *
 * Task ids are free-form strings. The platforms don't constrain their shape —
 * iOS `BGTaskScheduler` and Android WorkManager both accept any string and
 * match it byte-for-byte — so neither do we. The checks here exist only to
 * catch the two mistakes that fail silently at runtime: an empty id (iOS never
 * fires it) and a copy-pasted id with stray whitespace that no longer matches
 * its `BGTaskSchedulerPermittedIdentifiers` entry. Control characters are
 * rejected because [EphemeralRegistry] persists ids joined by U+001F and an id
 * containing it would corrupt that record.
 *
 * Reverse-DNS (`com.example.app.sync`) is the *documented convention*, not a
 * rule: it's Apple's recommendation and keeps ids from colliding with other
 * libraries' identifiers in the same app. See docs/concepts/task-ids.md.
 *
 * Called at every public entry point that accepts an id — [WorkerRegistry.register],
 * [WorkRequest] construction, [Backgrounder.runNow], and the iOS tick identifier —
 * so Swift callers (who bypass Kotlin constructors) are validated too.
 *
 * @throws IllegalArgumentException if [taskId] is blank, has surrounding whitespace, or contains control characters.
 */
internal fun requireValidTaskId(
    taskId: String,
    what: String = "task id",
) {
    require(taskId.isNotBlank()) { "$what must not be blank" }
    require(taskId.trim() == taskId) { "$what must not have leading or trailing whitespace; was '$taskId'" }
    require(taskId.none { it.isISOControl() }) { "$what must not contain control characters; was '$taskId'" }
}
