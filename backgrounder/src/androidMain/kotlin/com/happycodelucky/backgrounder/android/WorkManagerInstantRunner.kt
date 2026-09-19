package com.happycodelucky.backgrounder.android

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.InstantRunner
import com.happycodelucky.backgrounder.PendingInstantCalls
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android [InstantRunner] backed by `WorkManager`. The lambda runs inside
 * an [InstantDispatchWorker] (a [androidx.work.CoroutineWorker]); the
 * caller suspends on a `CompletableDeferred<R>` that the worker completes.
 *
 * Pre-emption is enforced by `Backgrounder.runNow` *before* this method is
 * invoked, so when we install our entry into [PendingInstantCalls] the slot
 * should be empty. We still defensively replace any stale entry — the same
 * defense iOS and macOS apply.
 */
internal class WorkManagerInstantRunner(
    private val workManagerProvider: () -> WorkManager,
    private val pending: PendingInstantCalls,
) : InstantRunner {
    private val log = Logger.withTag("Backgrounder/Android/InstantRunner")

    override suspend fun <R> run(
        taskId: String,
        task: suspend () -> R,
    ): R {
        val deferred = CompletableDeferred<Any?>()
        val erasedTask: suspend () -> Any? = { task() }
        val entry = PendingInstantCalls.Entry(taskId, erasedTask, deferred)

        // Defensive replace — see iOS / macOS runners for the rationale.
        pending.put(entry)?.let { prior ->
            prior.job?.cancel(CancellationException("pre-empted by newer runNow($taskId)"))
            prior.deferred.cancel(CancellationException("pre-empted by newer runNow($taskId)"))
        }

        val request =
            OneTimeWorkRequestBuilder<InstantDispatchWorker>()
                .setInputData(AndroidWorkInputMapper.toInstantData(taskId))
                .build()
        // The work id is needed for caller-side cancellation — store it on the
        // entry so the `try/finally` cleanup can reach it.
        entry.platformHandle = request.id

        val workManager = workManagerProvider()
        // `REPLACE` is defense-in-depth: pre-emption already cancelled the
        // prior, but if a race lets one slip through, REPLACE wins it.
        workManager.enqueueUniqueWork(uniqueWorkName(taskId), ExistingWorkPolicy.REPLACE, request)

        return try {
            @Suppress("UNCHECKED_CAST")
            deferred.await() as R
        } finally {
            // If still in the slot (no pre-emption replaced us), drop it.
            pending.removeIfSame(taskId, entry)
            // Best-effort: cancel the WorkManager request. If it has already
            // started, WorkManager will deliver a CancellationException via
            // onStopped to the InstantDispatchWorker.
            runCatching { workManager.cancelWorkById(request.id) }
                .onFailure { log.d(it) { "cancelWorkById($taskId/${request.id}) failed in cleanup" } }
        }
    }

    override fun cancelInFlight(taskId: String): Boolean {
        val entry = pending.take(taskId) ?: return false
        val workId = entry.platformHandle
        entry.deferred.cancel(CancellationException("Backgrounder.cancel($taskId)"))
        if (workId is java.util.UUID) {
            runCatching { workManagerProvider().cancelWorkById(workId) }
                .onFailure { log.d(it) { "cancelWorkById($taskId/$workId) failed" } }
        }
        return true
    }

    internal companion object {
        /**
         * The unique-work key for an instant dispatch under [taskId]. Distinct
         * suffix (`::runNow`) keeps it disjoint from the scheduled path's
         * unique-work key (which is just `taskId`), so a `runNow` and a
         * `schedule` for the same task id don't collide at the WorkManager
         * level.
         */
        internal fun uniqueWorkName(taskId: String): String = "$taskId::runNow"
    }
}
