package com.happycodelucky.backgrounder

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [InstantRunner] for unit tests.
 *
 * Records calls and lets tests drive in-flight `runNow` invocations
 * deterministically — `run(taskId, task)` records the call, immediately
 * invokes [task] inline, and completes the deferred with the result. For
 * tests that need to *suspend* a `runNow` mid-flight to exercise pre-emption
 * or cancellation, use [runWithDeferred] which exposes the underlying
 * `CompletableDeferred<Any?>` for the test to control.
 *
 * Calls to [cancelInFlight] are recorded so [BackgroundTaskManager.cancel] tests can
 * assert the unified surface forwards correctly.
 */
internal class FakeInstantRunner : InstantRunner {
    private val lock = SynchronizedObject()
    private val inflight: MutableMap<String, CompletableDeferred<Any?>> = mutableMapOf()

    /** Number of `run(...)` calls observed. */
    val runCount: Int get() = runCounter.value

    /** Number of `cancelInFlight(...)` calls that returned true. */
    val cancelHits: Int get() = cancelHitCounter.value

    /** Number of `cancelInFlight(...)` calls that returned false. */
    val cancelMisses: Int get() = cancelMissCounter.value

    private val runCounter = atomic(0)
    private val cancelHitCounter = atomic(0)
    private val cancelMissCounter = atomic(0)

    override suspend fun <R> run(
        taskId: String,
        task: suspend () -> R,
    ): R {
        runCounter.incrementAndGet()
        val deferred = CompletableDeferred<Any?>()
        synchronized(lock) {
            // Mirror the per-platform single-slot semantics: a second run for
            // the same id displaces the first.
            inflight.put(taskId, deferred)
        }
        return try {
            val result = task()
            deferred.complete(result)
            @Suppress("UNCHECKED_CAST")
            result
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            synchronized(lock) {
                if (inflight[taskId] === deferred) inflight.remove(taskId)
            }
        }
    }

    override fun cancelInFlight(taskId: String): Boolean {
        val deferred =
            synchronized(lock) {
                inflight.remove(taskId)
            }
        return if (deferred != null) {
            deferred.cancel()
            cancelHitCounter.incrementAndGet()
            true
        } else {
            cancelMissCounter.incrementAndGet()
            false
        }
    }
}
