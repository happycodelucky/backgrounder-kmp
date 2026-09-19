package com.happycodelucky.backgrounder

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Common-side tests for `Backgrounder.runNow` and `Backgrounder.cancel`.
 *
 * Uses a hand-built [BackgrounderEngine] with a [FakeScheduler] and a
 * [FakeInstantRunner] — exercises the pure [Backgrounder] glue (start gate,
 * pre-emption forwarding, cancel merge logic) without any platform plumbing.
 */
class RunNowTest {
    private val taskId = "com.happycodelucky.backgrounder.test.runNow"
    private val otherId = "com.happycodelucky.backgrounder.test.other"

    private fun build(): Triple<Backgrounder, FakeScheduler, FakeInstantRunner> {
        val ephemeral = EphemeralRegistry(MapSettings())
        val scheduler = FakeScheduler(ephemeral)
        val runner = FakeInstantRunner()
        val backgrounder =
            Backgrounder(
                BackgrounderEngine(
                    registry = WorkerRegistry(),
                    scheduler = scheduler,
                    instantRunner = runner,
                    emitter = MonitorEventEmitter(BackgrounderEventListener.Noop),
                    onStart = {},
                    onShutdown = {},
                ),
            )
        return Triple(backgrounder, scheduler, runner)
    }

    @Test
    fun runNowBeforeStartThrowsIllegalState() =
        runTest {
            val (backgrounder, _, _) = build()
            assertFailsWith<IllegalStateException> {
                backgrounder.runNow(taskId) { "anything" }
            }
        }

    @Test
    fun runNowReturnsLambdaResult() =
        runTest {
            val (backgrounder, _, runner) = build()
            backgrounder.start()

            val result = backgrounder.runNow(taskId) { "ok" }
            assertEquals("ok", result)
            assertEquals(1, runner.runCount)
        }

    @Test
    fun runNowPropagatesLambdaException() =
        runTest {
            val (backgrounder, _, _) = build()
            backgrounder.start()

            class Boom : RuntimeException("boom")
            val thrown =
                assertFailsWith<Boom> {
                    backgrounder.runNow(taskId) { throw Boom() }
                }
            assertEquals("boom", thrown.message)
        }

    @Test
    fun runNowSupportsTypedReturn() =
        runTest {
            val (backgrounder, _, _) = build()
            backgrounder.start()

            val length: Int = backgrounder.runNow(taskId) { "hello".length }
            assertEquals(5, length)
        }

    @Test
    fun runNowCallsCancelOnceBeforeRunningToEnforcePreEmption() =
        runTest {
            val (backgrounder, scheduler, runner) = build()
            backgrounder.start()

            backgrounder.runNow(taskId) { "ok" }

            // Pre-emption invariant: every runNow first calls cancel(taskId).
            // FakeInstantRunner's cancelMisses counts no-op cancels, so we expect
            // exactly one (the pre-emption attempt — nothing was in flight yet).
            assertEquals(1, runner.cancelMisses)
            assertEquals(0, runner.cancelHits)
        }

    @Test
    fun cancelMergesSchedulerAndInstantRunnerOutcomes() =
        runTest {
            val (backgrounder, scheduler, runner) = build()
            backgrounder.start()

            // Nothing scheduled, nothing in flight → NoSuchTask.
            assertEquals(CancelOutcome.NoSuchTask, backgrounder.cancel(taskId))

            // Scheduler has one pending → Cancelled(1).
            scheduler.schedule(WorkRequest.OneTime(taskId))
            val outcome = backgrounder.cancel(taskId)
            assertTrue(outcome is CancelOutcome.Cancelled)
            assertEquals(1, outcome.pendingCleared)
        }

    @Test
    fun cancelUpgradesNoSuchTaskToCancelledZeroWhenInstantRunnerHadInFlight() =
        runTest {
            val (backgrounder, _, runner) = build()
            backgrounder.start()
            // Stage an in-flight runNow that the test will keep alive long
            // enough to hit cancel.
            val gate = CompletableDeferred<String>()
            val job =
                launch {
                    runCatching { backgrounder.runNow(taskId) { gate.await() } }
                }
            // Give the launch a chance to register the entry.
            yield()

            val outcome = backgrounder.cancel(taskId)
            // FakeScheduler has no scheduled entry → NoSuchTask. The runner
            // has an in-flight entry → cancelInFlight returns true. The
            // unified Backgrounder.cancel must upgrade to Cancelled(0).
            assertEquals(CancelOutcome.Cancelled(pendingCleared = 0), outcome)
            assertEquals(1, runner.cancelHits)

            gate.complete("never reached")
            job.join()
        }

    @Test
    fun cancelOnDifferentTaskIdDoesNotAffectInFlightRunNow() =
        runTest {
            val (backgrounder, _, runner) = build()
            backgrounder.start()

            val gate = CompletableDeferred<String>()
            val job =
                async {
                    backgrounder.runNow(taskId) { gate.await() }
                }
            yield()

            // Cancel a *different* id — must be a no-op for our in-flight call.
            assertEquals(CancelOutcome.NoSuchTask, backgrounder.cancel(otherId))
            assertEquals(0, runner.cancelHits)

            gate.complete("done")
            assertEquals("done", job.await())
        }

    @Test
    fun promotedSchedulingVerbsDelegateToScheduler() =
        runTest {
            val (backgrounder, scheduler, _) = build()

            // schedule → scheduler.schedule
            assertEquals(
                ScheduleOutcome.Scheduled,
                backgrounder.schedule(WorkRequest.OneTime(taskId)),
            )
            // scheduled → scheduler.scheduled
            assertEquals(listOf(taskId), backgrounder.scheduled().map { it.taskId })
            // guarantees → scheduler.guarantees
            assertEquals(scheduler.guarantees(), backgrounder.guarantees())
            // cancelAll → scheduler.cancelAll
            backgrounder.schedule(WorkRequest.OneTime(otherId))
            val outcome = backgrounder.cancelAll()
            assertTrue(outcome is CancelOutcome.Cancelled)
            assertEquals(2, outcome.pendingCleared)
            assertTrue(backgrounder.scheduled().isEmpty())
        }
}
