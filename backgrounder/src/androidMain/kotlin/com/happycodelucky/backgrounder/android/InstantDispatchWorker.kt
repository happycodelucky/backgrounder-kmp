package com.happycodelucky.backgrounder.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.PendingInstantCalls
import kotlin.coroutines.cancellation.CancellationException
import androidx.work.ListenableWorker.Result as AndroidResult

/**
 * The single `CoroutineWorker` that backs `Backgrounder.runNow` on Android.
 *
 * Reads the task id[com.happycodelucky.backgrounder.String] out of `inputData`, looks up
 * the in-flight entry in [PendingInstantCalls], invokes the entry's `task`
 * lambda, and completes the entry's `Deferred` with the result (or the
 * thrown exception). The original `runNow` caller — suspended on
 * `deferred.await()` inside `WorkManagerInstantRunner.run` — resumes with
 * the typed result `R`.
 *
 * **Why a separate worker class from [RegistryDispatchWorker].** The two
 * dispatch paths share *no* runtime semantics: `RegistryDispatchWorker`
 * looks up a `BackgroundWorker` factory in the [WorkerRegistry] and applies
 * scheduled-work mapping ([WorkResult] → [AndroidResult.retry] /
 * [AndroidResult.failure]); `InstantDispatchWorker` consults an in-process
 * single-slot map of pending `runNow` calls and bridges the result back to
 * a typed `Deferred`. Sharing a class would conflate the two and make
 * `WorkerFactory` routing fragile.
 *
 * **No retry mapping.** A thrown lambda is terminal: the caller's `await`
 * already saw the exception, and WorkManager has no reason to retry — there
 * is no `BackgroundWorker` to call again. We always return
 * [AndroidResult.success] for normal completion and [AndroidResult.failure]
 * for the thrown-or-no-pending-entry path.
 */
internal class InstantDispatchWorker(
    context: Context,
    params: WorkerParameters,
    private val pending: PendingInstantCalls,
) : CoroutineWorker(context, params) {
    private val log = Logger.withTag("Backgrounder/InstantWorker")

    override suspend fun doWork(): AndroidResult {
        val taskId =
            AndroidWorkInputMapper.readTaskId(inputData) ?: run {
                log.e { "InstantDispatchWorker fired without a task id in inputData" }
                return AndroidResult.failure()
            }

        val tagged = log.withTag("Backgrounder/InstantWorker/$taskId")

        // Pre-emption guarantees at most one entry per task id at any time, so
        // `take` is the right primitive. If no entry exists the runNow caller
        // has already moved on (cancellation, pre-emption, replaced) — the
        // OS-side dispatch is moot.
        val entry =
            pending.take(taskId) ?: run {
                tagged.i { "no pending entry — likely cancelled or pre-empted before dispatch" }
                return AndroidResult.success()
            }

        return try {
            val result = entry.task()
            entry.deferred.complete(result)
            AndroidResult.success()
        } catch (e: CancellationException) {
            tagged.i { "cancelled: ${e.message}" }
            entry.deferred.cancel(e)
            throw e
        } catch (t: Throwable) {
            tagged.d(t) { "lambda threw" }
            entry.deferred.completeExceptionally(t)
            AndroidResult.failure()
        }
    }
}
