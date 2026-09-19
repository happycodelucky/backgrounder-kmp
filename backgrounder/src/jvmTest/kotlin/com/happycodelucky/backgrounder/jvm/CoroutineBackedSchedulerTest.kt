package com.happycodelucky.backgrounder.jvm

import com.happycodelucky.backgrounder.AttemptFailureReason
import com.happycodelucky.backgrounder.BackgroundWorker
import com.happycodelucky.backgrounder.BackgrounderEventListener
import com.happycodelucky.backgrounder.BackoffPolicy
import com.happycodelucky.backgrounder.CancelOutcome
import com.happycodelucky.backgrounder.ConflictPolicy
import com.happycodelucky.backgrounder.DeferralReason
import com.happycodelucky.backgrounder.EphemeralRegistry
import com.happycodelucky.backgrounder.MonitorEvent
import com.happycodelucky.backgrounder.MonitorEventEmitter
import com.happycodelucky.backgrounder.NetworkRequirement
import com.happycodelucky.backgrounder.PendingPredicate
import com.happycodelucky.backgrounder.ReachabilityGate
import com.happycodelucky.backgrounder.ScheduleOutcome
import com.happycodelucky.backgrounder.ScheduledTask
import com.happycodelucky.backgrounder.SkipReason
import com.happycodelucky.backgrounder.WorkConstraints
import com.happycodelucky.backgrounder.WorkRequest
import com.happycodelucky.backgrounder.WorkResult
import com.happycodelucky.backgrounder.WorkerContext
import com.happycodelucky.backgrounder.WorkerRegistry
import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.Transport
import com.happycodelucky.reachable.testing.FakeReachability
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Virtual-time tests for [CoroutineBackedScheduler].
 *
 * The JVM scheduler is the one platform where the *entire* dispatch pipeline —
 * initial delay, periodic interval, backoff window, reachability-gate wait —
 * runs on injected machinery ([StandardTestDispatcher] + injected `Clock`,
 * per LESSONS.md N-011), so every timing assertion here is exact, not
 * approximate. The B-001 regression guard (first retry waits `delayFor(0)`,
 * not `delayFor(1)`) is asserted to the millisecond.
 */
class CoroutineBackedSchedulerTest {
    private val taskId = "com.happycodelucky.backgrounder.test.jvm"
    private val otherTaskId = "com.happycodelucky.backgrounder.test.jvm.other"

    // Same recognisable base as IOSPeriodicDispatcherTest (2026-01-01).
    private val epochBase: Long = 1_767_225_600_000L

    private class Rig(
        val scheduler: CoroutineBackedScheduler,
        val registry: WorkerRegistry,
        val ephemeral: EphemeralRegistry,
        val emitter: MonitorEventEmitter,
    )

    private fun TestScope.newRig(reachability: FakeReachability = onlineFake()): Rig {
        val ephemeral = EphemeralRegistry(MapSettings())
        val registry = WorkerRegistry()
        val emitter = MonitorEventEmitter(BackgrounderEventListener.Noop)
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.fromEpochMilliseconds(epochBase + testScheduler.currentTime)
            }
        val scheduler =
            CoroutineBackedScheduler(
                registry = registry,
                ephemeral = ephemeral,
                emitter = emitter,
                gate = ReachabilityGate(reachability),
                dispatcher = StandardTestDispatcher(testScheduler),
                clock = clock,
            )
        return Rig(scheduler, registry, ephemeral, emitter)
    }

    /** Subscribe before acting — the events flow has `replay = 0`. */
    private fun TestScope.collectEvents(rig: Rig): List<MonitorEvent> {
        val events = mutableListOf<MonitorEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            rig.emitter.events.collect { events += it }
        }
        return events
    }

    private class CountingWorker(
        private val outcome: () -> WorkResult = { WorkResult.Success },
    ) : BackgroundWorker {
        var runs: Int = 0
            private set

        override suspend fun execute(context: WorkerContext): WorkResult {
            runs += 1
            return outcome()
        }
    }

    @Test
    fun oneTimeFiresAfterInitialDelayThenClearsTracking() =
        runTest {
            val rig = newRig()
            val worker = CountingWorker()
            rig.registry.register(taskId) { worker }

            rig.scheduler.schedule(
                WorkRequest.OneTime(taskId = taskId, initialDelay = 5.minutes),
                ConflictPolicy.Replace,
            )

            advanceTimeBy(5.minutes - 1.milliseconds)
            runCurrent()
            assertEquals(0, worker.runs, "must not fire before the initial delay elapses")

            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(1, worker.runs)
            assertTrue(rig.scheduler.scheduled().isEmpty(), "terminal one-shot clears its tracking")
        }

    @Test
    fun keepPolicyKeepsTheExistingScheduleWithoutSideEffects() =
        runTest {
            val rig = newRig()
            val worker = CountingWorker()
            rig.registry.register(taskId) { worker }
            val events = collectEvents(rig)

            rig.scheduler.schedule(
                WorkRequest.OneTime(taskId = taskId, initialDelay = 10.minutes),
                ConflictPolicy.Replace,
            )
            runCurrent()

            val outcome =
                rig.scheduler.schedule(
                    WorkRequest.OneTime(taskId = taskId, initialDelay = 1.minutes),
                    ConflictPolicy.Keep,
                )
            assertEquals(ScheduleOutcome.Scheduled, outcome)
            runCurrent()

            // B-011 / B-022: the lost Keep race must be silent — one Scheduled,
            // no ScheduleReplaced, and the original fire time wins.
            assertEquals(1, events.filterIsInstance<MonitorEvent.Scheduled>().size)
            assertTrue(events.filterIsInstance<MonitorEvent.ScheduleReplaced>().isEmpty())

            advanceTimeBy(1.minutes)
            runCurrent()
            assertEquals(0, worker.runs, "the Keep-rejected 1-minute request must not fire")
            advanceTimeBy(9.minutes)
            runCurrent()
            assertEquals(1, worker.runs)
        }

    @Test
    fun replaceCancelsThePriorScheduleAndEmitsReplaced() =
        runTest {
            val rig = newRig()
            val worker = CountingWorker()
            rig.registry.register(taskId) { worker }
            val events = collectEvents(rig)

            rig.scheduler.schedule(
                WorkRequest.OneTime(taskId = taskId, initialDelay = 10.minutes),
                ConflictPolicy.Replace,
            )
            runCurrent()
            rig.scheduler.schedule(
                WorkRequest.OneTime(taskId = taskId, initialDelay = 2.minutes),
                ConflictPolicy.Replace,
            )
            runCurrent()

            assertEquals(1, events.filterIsInstance<MonitorEvent.ScheduleReplaced>().size)

            advanceTimeBy(2.minutes)
            runCurrent()
            assertEquals(1, worker.runs)

            // The displaced 10-minute schedule must never fire.
            advanceTimeBy(20.minutes)
            runCurrent()
            assertEquals(1, worker.runs)
        }

    @Test
    fun periodicFiresOncePerIntervalWithHonestNextRunHint() =
        runTest {
            val rig = newRig()
            val worker = CountingWorker()
            rig.registry.register(taskId) { worker }

            rig.scheduler.schedule(
                WorkRequest.Periodic(taskId = taskId, interval = 15.minutes),
                ConflictPolicy.Replace,
            )

            advanceTimeBy(15.minutes)
            runCurrent()
            assertEquals(1, worker.runs, "first fire happens after one full interval")
            advanceTimeBy(15.minutes)
            runCurrent()
            assertEquals(2, worker.runs)

            val snapshot = rig.scheduler.scheduled().single()
            assertEquals(ScheduledTask.Kind.Periodic, snapshot.kind)
            assertEquals(
                Instant.fromEpochMilliseconds(epochBase + 45.minutes.inWholeMilliseconds),
                snapshot.nextRunHint,
                "nextRunHint must come from the injected clock (N-011)",
            )

            rig.scheduler.shutdown()
        }

    @Test
    fun periodicRetryIncrementsAttemptUntilSuccessResets() =
        runTest {
            val rig = newRig()
            var calls = 0
            rig.registry.register(taskId) {
                BackgroundWorker { _ ->
                    calls += 1
                    if (calls <= 2) WorkResult.Retry else WorkResult.Success
                }
            }

            rig.scheduler.schedule(
                WorkRequest.Periodic(taskId = taskId, interval = 15.minutes),
                ConflictPolicy.Replace,
            )

            advanceTimeBy(15.minutes)
            runCurrent() // attempt 0 → Retry
            val afterFirstRetry = rig.scheduler.scheduled().single()
            assertEquals(1, afterFirstRetry.attempt)

            advanceTimeBy(15.minutes)
            runCurrent() // attempt 1 → Retry
            val afterSecondRetry = rig.scheduler.scheduled().single()
            assertEquals(2, afterSecondRetry.attempt)

            advanceTimeBy(15.minutes)
            runCurrent() // attempt 2 → Success resets the counter for the next cycle
            val afterSuccess = rig.scheduler.scheduled().single()
            assertEquals(0, afterSuccess.attempt)
            assertEquals(3, calls)

            rig.scheduler.shutdown()
        }

    @Test
    fun oneShotRetryWaitsExactlyDelayForOfTheFailedAttempt() =
        runTest {
            val rig = newRig()
            val worker = CountingWorker { WorkResult.Retry }
            rig.registry.register(taskId) { worker }
            val events = collectEvents(rig)

            rig.scheduler.schedule(
                WorkRequest.OneTime(
                    taskId = taskId,
                    backoff = BackoffPolicy.linear(initialDelay = 30.seconds, maxAttempts = 3),
                ),
                ConflictPolicy.Replace,
            )
            runCurrent()
            assertEquals(1, worker.runs, "attempt 0 fires immediately (zero initial delay)")

            // B-001: the first retry waits delayFor(0) == initialDelay — not delayFor(1).
            advanceTimeBy(30.seconds - 1.milliseconds)
            runCurrent()
            assertEquals(1, worker.runs)
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(2, worker.runs)

            // Second retry waits delayFor(1) == 60s (linear).
            advanceTimeBy(60.seconds - 1.milliseconds)
            runCurrent()
            assertEquals(2, worker.runs)
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(3, worker.runs)

            // nextAttempt == maxAttempts(3): give-up — no further fires, tracking dropped.
            advanceUntilIdle()
            assertEquals(3, worker.runs)
            assertTrue(rig.scheduler.scheduled().isEmpty())
            assertEquals(
                listOf(0, 1, 2),
                events.filterIsInstance<MonitorEvent.WorkStarted>().map { it.attempt },
            )
        }

    @Test
    fun backoffWindowSurfacesInTheScheduledSnapshot() =
        runTest {
            val rig = newRig()
            var calls = 0
            rig.registry.register(taskId) {
                BackgroundWorker { _ ->
                    calls += 1
                    if (calls == 1) WorkResult.Retry else WorkResult.Success
                }
            }

            rig.scheduler.schedule(
                WorkRequest.OneTime(
                    taskId = taskId,
                    backoff = BackoffPolicy.linear(initialDelay = 30.seconds, maxAttempts = 5),
                ),
                ConflictPolicy.Replace,
            )
            runCurrent() // attempt 0 → Retry → backoff window begins

            val snapshot = rig.scheduler.scheduled().single()
            assertEquals(ScheduledTask.State.Backoff, snapshot.state)
            assertEquals(1, snapshot.attempt)
            val predicate = snapshot.pendingPredicates.filterIsInstance<PendingPredicate.WaitingForBackoff>().single()
            assertEquals(
                Instant.fromEpochMilliseconds(epochBase + 30.seconds.inWholeMilliseconds),
                predicate.until,
                "backoff release time must come from the injected clock (N-011)",
            )

            advanceUntilIdle()
            assertEquals(2, calls)
            assertTrue(rig.scheduler.scheduled().isEmpty())
        }

    @Test
    fun cancelInterruptsAnInFlightWorker() =
        runTest {
            val rig = newRig()
            var observedCancellation = false
            rig.registry.register(taskId) {
                BackgroundWorker { _ ->
                    try {
                        delay(10.minutes)
                        WorkResult.Success
                    } catch (e: CancellationException) {
                        observedCancellation = true
                        throw e
                    }
                }
            }

            rig.scheduler.schedule(WorkRequest.OneTime(taskId = taskId), ConflictPolicy.Replace)
            runCurrent() // worker starts and suspends in its delay

            assertEquals(CancelOutcome.Cancelled(pendingCleared = 1), rig.scheduler.cancel(taskId))
            runCurrent() // deliver the cancellation
            assertTrue(observedCancellation, "cancelsInFlight = true: a running worker is interrupted")
            assertTrue(rig.scheduler.scheduled().isEmpty())
            assertEquals(CancelOutcome.NoSuchTask, rig.scheduler.cancel(taskId))
        }

    @Test
    fun cancelOfUnknownTaskReturnsNoSuchTask() =
        runTest {
            val rig = newRig()
            assertEquals(CancelOutcome.NoSuchTask, rig.scheduler.cancel(taskId))
        }

    @Test
    fun cancelAllCancelsEverythingExactlyOnce() =
        runTest {
            val rig = newRig()
            val oneShot = CountingWorker()
            val periodic = CountingWorker()
            rig.registry.register(taskId) { oneShot }
            rig.registry.register(otherTaskId) { periodic }
            rig.scheduler.schedule(
                WorkRequest.OneTime(taskId = taskId, initialDelay = 5.minutes),
                ConflictPolicy.Replace,
            )
            rig.scheduler.schedule(
                WorkRequest.Periodic(taskId = otherTaskId, interval = 15.minutes),
                ConflictPolicy.Replace,
            )
            runCurrent()

            assertEquals(CancelOutcome.Cancelled(pendingCleared = 2), rig.scheduler.cancelAll())
            assertTrue(rig.scheduler.scheduled().isEmpty())
            assertEquals(CancelOutcome.NoSuchTask, rig.scheduler.cancelAll())

            advanceUntilIdle()
            assertEquals(0, oneShot.runs, "nothing fires after cancelAll")
            assertEquals(0, periodic.runs, "nothing fires after cancelAll")
        }

    @Test
    fun scheduledReportsEphemeralHonestlyAndTerminalRunClearsTheMarker() =
        runTest {
            val rig = newRig()
            rig.registry.register(taskId) { CountingWorker() }

            rig.scheduler.schedule(
                WorkRequest.OneTime(taskId = taskId, initialDelay = 5.minutes, ephemeral = true),
                ConflictPolicy.Replace,
            )

            val snapshot = rig.scheduler.scheduled().single()
            assertEquals(ScheduledTask.State.Pending, snapshot.state)
            assertTrue(snapshot.ephemeral, "JVM reports the real ephemeral flag, not a placeholder")
            assertEquals(
                Instant.fromEpochMilliseconds(epochBase + 5.minutes.inWholeMilliseconds),
                snapshot.nextRunHint,
            )
            assertTrue(taskId in rig.ephemeral.snapshot())

            advanceUntilIdle()
            assertTrue(rig.scheduler.scheduled().isEmpty())
            assertTrue(
                rig.ephemeral.snapshot().isEmpty(),
                "a terminal one-shot must not leave a ghost ephemeral marker (B-023)",
            )
        }

    @Test
    fun reachabilityGateTimeoutDefersAsRetryThenRunsWhenReachable() =
        runTest {
            val offline = FakeReachability()
            val rig = newRig(offline)
            val worker = CountingWorker()
            rig.registry.register(taskId) { worker }
            val events = collectEvents(rig)

            rig.scheduler.schedule(
                WorkRequest.OneTime(
                    taskId = taskId,
                    constraints = WorkConstraints(networkRequired = NetworkRequirement.Any),
                    backoff = BackoffPolicy.linear(initialDelay = 30.seconds, maxAttempts = 5),
                ),
                ConflictPolicy.Replace,
            )

            // Attempt 0: the gate waits its bounded window against the offline
            // fake, times out, and the attempt defers as Retry — the worker
            // must not run.
            advanceTimeBy(10.seconds) // comfortably past the gate's bounded wait
            runCurrent()
            assertEquals(0, worker.runs, "worker must not run while the gate requirement is unmet")
            val deferred = events.filterIsInstance<MonitorEvent.AttemptDeferred>().single()
            assertIs<DeferralReason.ReachabilityTimeout>(deferred.reason)
            assertEquals(1, events.filterIsInstance<MonitorEvent.RetryScheduled>().size)

            // Network comes back; the backoff release runs the worker.
            offline.setReachable(true)
            advanceTimeBy(31.seconds)
            runCurrent()
            assertEquals(1, worker.runs)
        }

    @Test
    fun unmeteredRequirementHoldsOutForAnUnmeteredTransport() =
        runTest {
            val metered =
                FakeReachability(
                    ReachabilityStatus(isReachable = true, transport = Transport.Cellular, isDataMetered = true),
                )
            val rig = newRig(metered)
            val worker = CountingWorker()
            rig.registry.register(taskId) { worker }

            rig.scheduler.schedule(
                WorkRequest.OneTime(
                    taskId = taskId,
                    constraints = WorkConstraints(networkRequired = NetworkRequirement.Unmetered),
                    backoff = BackoffPolicy.linear(initialDelay = 30.seconds, maxAttempts = 5),
                ),
                ConflictPolicy.Replace,
            )

            advanceTimeBy(10.seconds)
            runCurrent()
            assertEquals(0, worker.runs, "metered connectivity must not satisfy Unmetered")

            metered.setDataMetered(false)
            advanceTimeBy(31.seconds)
            runCurrent()
            assertEquals(1, worker.runs)
        }

    @Test
    fun noFactoryRegisteredSkipsStructurallyWithoutRetry() =
        runTest {
            val rig = newRig()
            val events = collectEvents(rig)

            rig.scheduler.schedule(WorkRequest.OneTime(taskId = taskId), ConflictPolicy.Replace)
            advanceUntilIdle()
            // The collector runs in backgroundScope; advanceUntilIdle stops once
            // foreground work is idle, which can leave the *final* event's
            // collector resumption queued. runCurrent() flushes it.
            runCurrent()

            val skipped = events.filterIsInstance<MonitorEvent.Skipped>().single()
            assertEquals(SkipReason.NotRegistered, skipped.reason)
            assertTrue(rig.scheduler.scheduled().isEmpty())
            assertTrue(
                events.filterIsInstance<MonitorEvent.RetryScheduled>().isEmpty(),
                "structural skip must not retry (MonitorEvent.Skipped contract)",
            )
        }

    @Test
    fun workerThrowIsSurfacedAndRetriedPerBackoff() =
        runTest {
            val rig = newRig()
            var calls = 0
            rig.registry.register(taskId) {
                BackgroundWorker { _ ->
                    calls += 1
                    if (calls == 1) throw IllegalStateException("boom") else WorkResult.Success
                }
            }
            val events = collectEvents(rig)

            rig.scheduler.schedule(
                WorkRequest.OneTime(
                    taskId = taskId,
                    backoff = BackoffPolicy.linear(initialDelay = 30.seconds, maxAttempts = 5),
                ),
                ConflictPolicy.Replace,
            )
            advanceUntilIdle()

            assertEquals(2, calls, "throwing attempt converts to Retry; the retry succeeds")
            val failed = events.filterIsInstance<MonitorEvent.AttemptFailed>().single()
            assertIs<AttemptFailureReason.WorkerThrew>(failed.reason)
            assertTrue(rig.scheduler.scheduled().isEmpty())
        }

    private companion object {
        private fun onlineFake(): FakeReachability =
            FakeReachability(
                ReachabilityStatus(isReachable = true, transport = Transport.Wifi, isDataMetered = false),
            )
    }
}
