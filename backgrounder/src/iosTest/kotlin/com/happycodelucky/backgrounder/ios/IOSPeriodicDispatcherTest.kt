package com.happycodelucky.backgrounder.ios

import com.happycodelucky.backgrounder.BackgroundWorker
import com.happycodelucky.backgrounder.BackgrounderEventListener
import com.happycodelucky.backgrounder.EphemeralRegistry
import com.happycodelucky.backgrounder.NetworkRequirement
import com.happycodelucky.backgrounder.PlatformCapabilities
import com.happycodelucky.backgrounder.ReachabilityGate
import com.happycodelucky.backgrounder.WorkInput
import com.happycodelucky.backgrounder.WorkResult
import com.happycodelucky.backgrounder.WorkerContext
import com.happycodelucky.backgrounder.WorkerRegistry
import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.Transport
import com.happycodelucky.reachable.testing.FakeReachability
import com.russhwolf.settings.MapSettings
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Pure-logic tests for [IOSPeriodicDispatcher].
 *
 * The dispatcher is the load-bearing piece of the iOS periodic-dispatch
 * model — both feeds (foreground in-process timer, background `BGAppRefreshTaskRequest`)
 * call into it. Its correctness contract is the **mutex-then-advance**
 * pattern: when racing dispatches arrive for the same task id, only one
 * worker runs per cycle. This test class exercises that contract along
 * with the basic single-task / multi-task / retry / give-up flows.
 *
 * Time is driven by [TestScope.testScheduler.currentTime] via a
 * [virtualClock] helper, so all "now" checks are deterministic. The store
 * is backed by [MapSettings] (in-memory), which exercises the same
 * `IOSStateStore` codepaths that real `NSUserDefaults` would.
 *
 * Real `BGTask` interactions are not exercised here — those live in the
 * background-feed integration story and require a real iOS process. See
 * `CompletionGuardTest` for the same "we test the invariant, not the
 * unreproducible race" approach.
 */
class IOSPeriodicDispatcherTest {
    private val periodicId = "com.happycodelucky.backgrounder.test.periodic"
    private val otherId = "com.happycodelucky.backgrounder.test.periodic2"

    // Small but realistic budget so workers don't have to think about it.
    private val capabilities = PlatformCapabilities(maxExecutionTime = 5.minutes, cancelsInFlight = false)

    // Pin the "epoch base" to a recognizable timestamp; tests advance virtual
    // time on top of it so the persisted nextRunEpochMs values look like real
    // millisecond timestamps. Choosing 2026-01-01 for legibility — the absolute
    // value doesn't matter, only that the deltas are correct.
    private val epochBase: Long = 1_767_225_600_000L

    private val ownedScopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun cleanupScopes() {
        ownedScopes.forEach { it.cancel() }
        ownedScopes.clear()
    }

    // --- helpers ------------------------------------------------------------

    /** A virtual-time clock that returns [epochBase] + the test scheduler's currentTime. */
    private fun TestScope.virtualClock(): () -> Long = { epochBase + testScheduler.currentTime }

    private fun newRig(scope: TestScope): Rig {
        val store = IOSStateStore(MapSettings())
        val mutexes = IOSTaskMutexes()
        val ephemeral = EphemeralRegistry(MapSettings())
        val registry = WorkerRegistry()
        val events = RecordingListener()
        // Always-reachable fake — the existing tests don't exercise the gate;
        // they just need it to short-circuit to Met so timing matches pre-gate
        // behaviour. The gate sits in front of `worker.execute(ctx)` and these
        // tests assert against execute being called, so a fake stuck at
        // reachable=true keeps the dispatcher's behaviour identical.
        val gate =
            ReachabilityGate(
                FakeReachability(
                    ReachabilityStatus(isReachable = true, transport = Transport.Wifi, isDataMetered = false),
                ),
            )
        val dispatcher =
            IOSPeriodicDispatcher(
                state = store,
                mutexes = mutexes,
                registry = registry,
                ephemeral = ephemeral,
                emitter = com.happycodelucky.backgrounder.MonitorEventEmitter(events),
                gate = gate,
                clock = scope.virtualClock(),
            )
        return Rig(store, mutexes, registry, events, dispatcher)
    }

    /** Schedule a periodic at the current virtual time. nextRunEpochMs = now + intervalMs. */
    private fun TestScope.schedulePeriodic(
        rig: Rig,
        id: String,
        interval: Duration,
    ) {
        val now = epochBase + testScheduler.currentTime
        rig.store.writeOnSchedule(
            taskId = id,
            kind = IOSStateStore.Kind.Periodic,
            input = WorkInput.empty(),
            ephemeral = false,
            intervalMs = interval.inWholeMilliseconds,
            nextRunEpochMs = now + interval.inWholeMilliseconds,
            networkRequired = NetworkRequirement.None,
        )
    }

    private fun TestScope.makeOwnedScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + coroutineContext)
        ownedScopes.add(scope)
        return scope
    }

    private fun stubWorker(execute: suspend (WorkerContext) -> WorkResult): BackgroundWorker = BackgroundWorker { execute(it) }

    // --- tests --------------------------------------------------------------

    @Test
    fun noDueTasks_dispatchDueWorkReturnsImmediately() =
        runTest {
            val rig = newRig(this)
            // No periodics scheduled at all.
            val scope = makeOwnedScope()
            rig.dispatcher.dispatchDueWork(scope, capabilities)
            assertTrue(rig.events.started.isEmpty())
            assertTrue(rig.events.completed.isEmpty())
        }

    @Test
    fun singleDueTask_workerFiresAndAdvancesNextRun() =
        runTest {
            val rig = newRig(this)
            schedulePeriodic(rig, periodicId, 15.minutes)

            val invocations = atomic(0)
            rig.registry.register(periodicId) {
                stubWorker {
                    invocations.incrementAndGet()
                    WorkResult.Success
                }
            }

            // Advance virtual time past the scheduled nextRun.
            testScheduler.advanceTimeBy(20.minutes.inWholeMilliseconds)

            val scope = makeOwnedScope()
            rig.dispatcher.dispatchDueWork(scope, capabilities)

            assertEquals(1, invocations.value, "worker should fire exactly once")
            // After dispatch, nextRunEpochMs is advanced by intervalMs from the
            // dispatch's "now" (epochBase + 20 minutes), so it lands at
            // epochBase + 35 minutes.
            val expectedNext = epochBase + 20.minutes.inWholeMilliseconds + 15.minutes.inWholeMilliseconds
            assertEquals(expectedNext, rig.store.readNextRunEpochMs(periodicId))
            assertEquals(0, rig.store.readAttempt(periodicId))
        }

    @Test
    fun twoDueTasks_workersRunConcurrently() =
        runTest {
            val rig = newRig(this)
            schedulePeriodic(rig, periodicId, 15.minutes)
            schedulePeriodic(rig, otherId, 15.minutes)

            // Each worker awaits a "go" signal so we can verify they really run
            // in parallel — if they ran sequentially, the second worker would
            // never reach its await before the first finished.
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val firstCanFinish = CompletableDeferred<Unit>()
            val secondCanFinish = CompletableDeferred<Unit>()

            rig.registry.register(periodicId) {
                stubWorker {
                    firstStarted.complete(Unit)
                    firstCanFinish.await()
                    WorkResult.Success
                }
            }
            rig.registry.register(otherId) {
                stubWorker {
                    secondStarted.complete(Unit)
                    secondCanFinish.await()
                    WorkResult.Success
                }
            }

            testScheduler.advanceTimeBy(20.minutes.inWholeMilliseconds)
            val scope = makeOwnedScope()
            val dispatchJob = launch { rig.dispatcher.dispatchDueWork(scope, capabilities) }

            // Both workers should reach their "started" point before either
            // finishes — that's the parallelism evidence.
            firstStarted.await()
            secondStarted.await()
            firstCanFinish.complete(Unit)
            secondCanFinish.complete(Unit)
            dispatchJob.join()

            assertEquals(2, rig.events.completed.size)
            assertTrue(rig.events.completed.any { it.first == periodicId })
            assertTrue(rig.events.completed.any { it.first == otherId })
        }

    @Test
    fun concurrentDispatchCalls_workerFiresExactlyOnce() =
        runTest {
            // Two "parallel" dispatchDueWork calls hit the same due task.
            // The mutex-then-advance contract means the slower caller acquires
            // the mutex *after* the first caller advanced nextRunEpochMs, so
            // its inside-the-mutex re-check sees nextRunEpochMs > now() and
            // bails — the worker fires exactly once.
            //
            // This test runs on `runTest`'s single virtual thread, which means
            // the "race" is a deterministic interleaving rather than a
            // probabilistic real race. That's the right test surface here:
            // the contract is enforced by code structure (mutex-then-advance
            // ordering), not by JVM-level scheduling, so the deterministic
            // interleaving is sufficient evidence that the contract holds.
            val rig = newRig(this)
            schedulePeriodic(rig, periodicId, 15.minutes)

            val invocations = atomic(0)
            val workerHolds = CompletableDeferred<Unit>()
            rig.registry.register(periodicId) {
                stubWorker {
                    invocations.incrementAndGet()
                    workerHolds.await()
                    WorkResult.Success
                }
            }

            testScheduler.advanceTimeBy(20.minutes.inWholeMilliseconds)
            val scope = makeOwnedScope()

            // Two callers both ask the dispatcher to run "what's due."
            val a = launch { rig.dispatcher.dispatchDueWork(scope, capabilities) }
            val b = launch { rig.dispatcher.dispatchDueWork(scope, capabilities) }

            // Yield so both jobs get a chance to acquire-or-wait on the mutex.
            // Then release the worker so the first caller can finish.
            testScheduler.runCurrent()
            workerHolds.complete(Unit)
            listOf(a, b).joinAll()

            assertEquals(1, invocations.value, "worker should fire exactly once across two concurrent dispatch calls")
            assertEquals(1, rig.events.completed.size)
        }

    @Test
    fun workerReturnsRetry_attemptIncrementsAndNextRunOverridden() =
        runTest {
            val rig = newRig(this)
            schedulePeriodic(rig, periodicId, 15.minutes)
            rig.registry.register(periodicId) { stubWorker { WorkResult.Retry } }

            testScheduler.advanceTimeBy(20.minutes.inWholeMilliseconds)
            val scope = makeOwnedScope()
            rig.dispatcher.dispatchDueWork(scope, capabilities)

            assertEquals(1, rig.store.readAttempt(periodicId), "attempt should increment from 0 to 1")

            // Default backoff = exponential, initialDelay = 30s, delayFor(0) = 30s.
            // So nextRunEpochMs = now + 30s = epochBase + 20m + 30s.
            // BUT: the dispatcher clamps the backoff next-run to the regular
            // cycle (now + intervalMs). Since 30s < 15m, the backoff value wins.
            val now = epochBase + 20.minutes.inWholeMilliseconds
            val expectedBackoff = now + 30_000L
            assertEquals(expectedBackoff, rig.store.readNextRunEpochMs(periodicId))
        }

    @Test
    fun workerExceedsMaxAttempts_treatedAsFailureAndAttemptResets() =
        runTest {
            val rig = newRig(this)
            schedulePeriodic(rig, periodicId, 15.minutes)
            rig.registry.register(periodicId) { stubWorker { WorkResult.Retry } }

            // Pre-set attempt to one below the default cap so the next Retry
            // exhausts. Default maxAttempts = 10, so set to 9 — nextAttempt
            // would be 10, which triggers shouldGiveUp.
            rig.store.setAttempt(periodicId, 9)

            testScheduler.advanceTimeBy(20.minutes.inWholeMilliseconds)
            val scope = makeOwnedScope()
            rig.dispatcher.dispatchDueWork(scope, capabilities)

            assertEquals(0, rig.store.readAttempt(periodicId), "attempt should reset to 0 after give-up")
            // After give-up, the regular-cycle advance from runOne() stays:
            // nextRunEpochMs = now + intervalMs (no backoff override).
            val expectedNext = epochBase + 20.minutes.inWholeMilliseconds + 15.minutes.inWholeMilliseconds
            assertEquals(expectedNext, rig.store.readNextRunEpochMs(periodicId))
        }

    @Test
    fun soonestUpcomingNextRun_returnsEarliestAcrossActivePeriodics() =
        runTest {
            val rig = newRig(this)
            schedulePeriodic(rig, periodicId, 15.minutes)
            schedulePeriodic(rig, otherId, 30.minutes)

            // periodicId is due at epochBase + 15m; otherId at epochBase + 30m.
            // Soonest = epochBase + 15m.
            val expected = epochBase + 15.minutes.inWholeMilliseconds
            assertEquals(expected, rig.dispatcher.soonestUpcomingNextRun())
        }

    @Test
    fun soonestUpcomingNextRun_returnsNullWhenNoActivePeriodics() =
        runTest {
            val rig = newRig(this)
            assertNull(rig.dispatcher.soonestUpcomingNextRun())
        }

    @Test
    fun inactivePeriodicIsNotDispatched() =
        runTest {
            val rig = newRig(this)
            schedulePeriodic(rig, periodicId, 15.minutes)
            // Mark inactive (e.g. from a cancel that won the race).
            rig.store.setActive(periodicId, false)

            val invocations = atomic(0)
            rig.registry.register(periodicId) {
                stubWorker {
                    invocations.incrementAndGet()
                    WorkResult.Success
                }
            }

            testScheduler.advanceTimeBy(20.minutes.inWholeMilliseconds)
            val scope = makeOwnedScope()
            rig.dispatcher.dispatchDueWork(scope, capabilities)

            assertEquals(0, invocations.value, "inactive periodic should not be dispatched")
        }

    // --- supporting types ---------------------------------------------------

    private data class Rig(
        val store: IOSStateStore,
        val mutexes: IOSTaskMutexes,
        val registry: WorkerRegistry,
        val events: RecordingListener,
        val dispatcher: IOSPeriodicDispatcher,
    )

    private class RecordingListener : BackgrounderEventListener {
        val started = mutableListOf<Pair<String, Int>>()
        val completed = mutableListOf<Triple<String, Int, WorkResult>>()

        override fun onScheduled(
            taskId: String,
            request: com.happycodelucky.backgrounder.WorkRequest,
        ) = Unit

        override fun onStarted(
            taskId: String,
            attempt: Int,
        ) {
            started.add(taskId to attempt)
        }

        override fun onCompleted(
            taskId: String,
            attempt: Int,
            result: WorkResult,
        ) {
            completed.add(Triple(taskId, attempt, result))
        }

        override fun onCancelled(taskId: String) = Unit
    }
}
