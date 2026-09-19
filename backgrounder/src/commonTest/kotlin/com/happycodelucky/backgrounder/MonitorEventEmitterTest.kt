package com.happycodelucky.backgrounder

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Unit-level coverage of [MonitorEventEmitter] — the central seam through
 * which every internal emit-site eventually flows. Platform schedulers are
 * not exercised here; that's wave 2.
 *
 * What we're proving:
 *  1. The four v1-shape events (`Scheduled`, `WorkStarted`, `WorkCompleted`,
 *     `Cancelled`) reach the legacy [BackgrounderEventListener] *and* the
 *     [SharedFlow] — same payload, no drops, in order.
 *  2. The richer events (`ScheduleReplaced`, `AttemptDeferred`, `Skipped`,
 *     `AttemptFailed`, `RetryScheduled`, `LibraryError`) reach only the
 *     flow. The listener stays silent — protects the v1 listener
 *     contract (existing implementers keep compiling, see no surprise
 *     callbacks).
 *  3. A throwing listener does not crash the emit path. The library
 *     contract says "must not throw" but defence-in-depth here is cheap.
 *  4. The shared flow's `tryEmit` semantics — sub-buffer bursts arrive
 *     unmolested. Overflow behaviour (`DROP_OLDEST`) is taken on faith
 *     from `kotlinx.coroutines`; testing it would require pinning a
 *     collector and is out of scope for a unit test.
 */
class MonitorEventEmitterTest {
    private val taskId = TaskId("com.example.task")
    private val request: WorkRequest =
        WorkRequest.OneTime(
            taskId = taskId,
            initialDelay = 0.seconds,
        )

    @Test
    fun listener_sees_v1_events() =
        runTest {
            val captured = mutableListOf<String>()
            val listener =
                object : BackgrounderEventListener {
                    override fun onScheduled(
                        taskId: TaskId,
                        request: WorkRequest,
                    ) {
                        captured.add("scheduled:${taskId.value}")
                    }

                    override fun onStarted(
                        taskId: TaskId,
                        attempt: Int,
                    ) {
                        captured.add("started:${taskId.value}:$attempt")
                    }

                    override fun onCompleted(
                        taskId: TaskId,
                        attempt: Int,
                        result: WorkResult,
                    ) {
                        captured.add("completed:${taskId.value}:$attempt:${result::class.simpleName}")
                    }

                    override fun onCancelled(taskId: TaskId) {
                        captured.add("cancelled:${taskId.value}")
                    }
                }
            val emitter = MonitorEventEmitter(listener)

            val now = Instant.fromEpochMilliseconds(0)
            emitter.emit(MonitorEvent.Scheduled(taskId, now, request))
            emitter.emit(MonitorEvent.WorkStarted(taskId, now, attempt = 0, expectedAt = null))
            emitter.emit(
                MonitorEvent.WorkCompleted(
                    taskId = taskId,
                    at = now,
                    attempt = 0,
                    result = WorkResult.Success,
                    runtime = 1.seconds,
                ),
            )
            emitter.emit(MonitorEvent.Cancelled(taskId, now, CancelSource.User))

            assertEquals(
                listOf(
                    "scheduled:com.example.task",
                    "started:com.example.task:0",
                    "completed:com.example.task:0:Success",
                    "cancelled:com.example.task",
                ),
                captured,
            )
        }

    @Test
    fun listener_does_not_see_richer_events() =
        runTest {
            val touches = mutableListOf<String>()
            val listener =
                object : BackgrounderEventListener {
                    override fun onScheduled(
                        taskId: TaskId,
                        request: WorkRequest,
                    ) {
                        touches.add("scheduled")
                    }

                    override fun onStarted(
                        taskId: TaskId,
                        attempt: Int,
                    ) {
                        touches.add("started")
                    }

                    override fun onCompleted(
                        taskId: TaskId,
                        attempt: Int,
                        result: WorkResult,
                    ) {
                        touches.add("completed")
                    }

                    override fun onCancelled(taskId: TaskId) {
                        touches.add("cancelled")
                    }
                }
            val emitter = MonitorEventEmitter(listener)

            val now = Instant.fromEpochMilliseconds(0)
            emitter.emit(
                MonitorEvent.ScheduleReplaced(
                    taskId = taskId,
                    at = now,
                    policy = ConflictPolicy.Replace,
                    current = request,
                ),
            )
            emitter.emit(
                MonitorEvent.AttemptDeferred(
                    taskId = taskId,
                    at = now,
                    attempt = 0,
                    reason = DeferralReason.NoMatchingTick,
                ),
            )
            emitter.emit(MonitorEvent.Skipped(taskId, now, SkipReason.NotRegistered))
            emitter.emit(
                MonitorEvent.AttemptFailed(
                    taskId = taskId,
                    at = now,
                    attempt = 0,
                    reason = AttemptFailureReason.ExpiredByOS,
                ),
            )
            emitter.emit(
                MonitorEvent.RetryScheduled(
                    taskId = taskId,
                    at = now,
                    nextAttempt = 1,
                    delay = 30.seconds,
                    nextRunHint = null,
                ),
            )
            emitter.emit(MonitorEvent.LibraryError(taskId, now, "boom", cause = null))

            assertTrue(touches.isEmpty(), "Richer events must not reach the v1 listener; saw: $touches")
        }

    @Test
    fun flow_sees_every_event_in_order() =
        runTest {
            val emitter = MonitorEventEmitter(BackgrounderEventListener.Noop)
            val now = Instant.fromEpochMilliseconds(0)
            val events =
                listOf(
                    MonitorEvent.Scheduled(taskId, now, request),
                    MonitorEvent.ScheduleReplaced(taskId, now, ConflictPolicy.Replace, request),
                    MonitorEvent.WorkStarted(taskId, now, attempt = 0, expectedAt = null),
                    MonitorEvent.AttemptDeferred(
                        taskId = taskId,
                        at = now,
                        attempt = 0,
                        reason =
                            DeferralReason.ReachabilityTimeout(
                                requirement = NetworkRequirement.Any,
                                waited = 5.seconds,
                            ),
                    ),
                    MonitorEvent.WorkCompleted(taskId, now, 0, WorkResult.Retry, 1.seconds),
                    MonitorEvent.RetryScheduled(taskId, now, nextAttempt = 1, delay = 30.seconds, nextRunHint = null),
                    MonitorEvent.Skipped(taskId, now, SkipReason.FactoryDeclined),
                    MonitorEvent.AttemptFailed(taskId, now, 0, AttemptFailureReason.WorkerThrew(RuntimeException("x"))),
                    MonitorEvent.LibraryError(taskId, now, "submit failed", cause = null),
                    MonitorEvent.Cancelled(taskId, now, CancelSource.Shutdown),
                )

            emitter.events.test {
                events.forEach { emitter.emit(it) }
                events.forEach { expected ->
                    val actual = awaitItem()
                    assertSame(expected, actual, "Event mismatch — expected $expected, got $actual")
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun throwing_listener_does_not_crash_emit() =
        runTest {
            val throwing =
                object : BackgrounderEventListener {
                    override fun onScheduled(
                        taskId: TaskId,
                        request: WorkRequest,
                    ) {
                        error("listener exploded")
                    }

                    override fun onStarted(
                        taskId: TaskId,
                        attempt: Int,
                    ) = Unit

                    override fun onCompleted(
                        taskId: TaskId,
                        attempt: Int,
                        result: WorkResult,
                    ) = Unit

                    override fun onCancelled(taskId: TaskId) = Unit
                }
            val emitter = MonitorEventEmitter(throwing)
            val now = Instant.fromEpochMilliseconds(0)

            emitter.events.test {
                emitter.emit(MonitorEvent.Scheduled(taskId, now, request))
                assertTrue(awaitItem() is MonitorEvent.Scheduled)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
