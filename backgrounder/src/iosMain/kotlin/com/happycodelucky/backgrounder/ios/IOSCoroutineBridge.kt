package com.happycodelucky.backgrounder.ios

import co.touchlab.kermit.Logger
import com.happycodelucky.backgrounder.AttemptFailureReason
import com.happycodelucky.backgrounder.CompletionGuard
import com.happycodelucky.backgrounder.DeferralReason
import com.happycodelucky.backgrounder.MonitorEvent
import com.happycodelucky.backgrounder.MonitorEventEmitter
import com.happycodelucky.backgrounder.PlatformCapabilities
import com.happycodelucky.backgrounder.ReachabilityGate
import com.happycodelucky.backgrounder.SkipReason
import com.happycodelucky.backgrounder.WorkResult
import com.happycodelucky.backgrounder.WorkerContext
import com.happycodelucky.backgrounder.WorkerRegistry
import com.happycodelucky.backgrounder.gateBudgetFor
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTask
import platform.BackgroundTasks.BGTask
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Drives a per-task id [BGTask] handler closure from the OS — the
 * **one-shot** dispatch path.
 *
 * Post step-6 cut-over, periodics no longer flow through this bridge —
 * they're driven by [IOSPeriodicDispatcher] via the
 * [IOSForegroundFeed] / [IOSBackgroundFeed] pair, which use a single
 * library-owned tick identifier instead of per-task id registrations.
 * This bridge handles only [WorkRequest.OneTime] dispatches; the per-id
 * launch handler registration in [BGTaskHandlerRegistration.registerOne]
 * is conservatively wired for every registered factory id, but periodics
 * never reach it because nothing submits per-id requests for them.
 *
 * The OS calls a Swift / Obj-C closure on its own queue. We launch user code
 * on a [SupervisorJob]-rooted scope (CLAUDE.md §3 forbids `GlobalScope`),
 * wire the task's [BGTask.expirationHandler] to cancel the [Job], and from
 * `invokeOnCompletion` mark the task `setTaskCompletedWithSuccess` only if
 * the worker hadn't already done so itself.
 *
 * Lifecycle of one fire:
 *  1. OS calls the registered closure with a [BGTask].
 *  2. We read attempt + input from [IOSStateStore] under the per-id [Mutex].
 *  3. We register the expiration handler **before** launching the worker —
 *     Apple's `BGTaskScheduler` documents that an unset expiration handler at
 *     the moment the system reclaims the task can crash the process. The
 *     handler captures the [Job] via a lateinit slot.
 *  4. We launch a coroutine that calls the worker.
 *  5. The worker returns a [WorkResult]; the bridge applies it via
 *     [applyResult] (which may resubmit, persist, mark completed). Every
 *     call to `setTaskCompletedWithSuccess` goes through a per-fire
 *     [CompletionGuard] so iOS's "completed twice" assertion can't trigger.
 *  6. If iOS expires before completion, the expiration handler cancels
 *     the job; `invokeOnCompletion` then runs the guarded fallback that
 *     reports `setTaskCompletedSuccess(false)`.
 */
internal class IOSCoroutineBridge(
    private val registry: WorkerRegistry,
    private val state: IOSStateStore,
    private val mutexes: IOSTaskMutexes,
    private val emitter: MonitorEventEmitter,
    private val gate: ReachabilityGate,
    private val applyResult: suspend (BGTask, String, Int, WorkResult, CompletionGuard) -> Unit,
) {
    private val log = Logger.withTag("Backgrounder/iOS")

    private val scope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("Backgrounder.iOS"),
        )

    fun handle(
        task: BGTask,
        taskId: String,
    ) {
        val tagged = log.withTag("Backgrounder/iOS/$taskId")
        // Snapshot attempt/input/networkRequired synchronously (off the main queue)
        // before launching. The `networkRequired` read drives the pre-execution
        // reachability gate further down. Defaults to `None` for v1-schema state.
        val attempt = state.readAttempt(taskId)
        val input = state.readInput(taskId)
        val networkRequired = state.readNetworkRequired(taskId)
        val startedAt = Clock.System.now()
        emitter.emit(
            MonitorEvent.WorkStarted(
                taskId = taskId,
                at = startedAt,
                attempt = attempt,
                expectedAt = state.readNextRunEpochMs(taskId)?.let { kotlin.time.Instant.fromEpochMilliseconds(it) },
            ),
        )
        tagged.d { "handle: attempt=$attempt networkRequired=$networkRequired" }

        val capabilities = capabilitiesFor(task)
        val guard = CompletionGuard()

        // ── M-1: register the expiration handler BEFORE launching the worker.
        // Apple's BGTaskScheduler can fire expiration any time after the handler
        // closure returns; if `setExpirationHandler` is unset at that moment, the
        // system kills the process. Using a lateinit job slot lets us bind the
        // handler now and the job after `launch` returns. The handler is a
        // value-capture closure — it reads `job` only when the system invokes it,
        // which is necessarily after `launch` has assigned the field.
        var job: Job? = null
        task.setExpirationHandler {
            tagged.w { "BGTask expired; cancelling worker" }
            job?.cancel(CancellationException("BGTask expired"))
        }

        job =
            scope.launch {
                mutexes.withMutex(taskId) {
                    val worker =
                        try {
                            registry.create(taskId)
                        } catch (e: WorkerRegistry.NoFactoryRegisteredException) {
                            tagged.e(e) { "no factory registered; treating as Failure" }
                            emitter.emit(
                                MonitorEvent.Skipped(
                                    taskId = taskId,
                                    at = Clock.System.now(),
                                    reason = SkipReason.NotRegistered,
                                ),
                            )
                            val failure = WorkResult.Failure("no factory: $taskId")
                            applyResult(task, taskId, attempt, failure, guard)
                            emitter.emit(
                                MonitorEvent.WorkCompleted(
                                    taskId = taskId,
                                    at = Clock.System.now(),
                                    attempt = attempt,
                                    result = failure,
                                    runtime = Clock.System.now() - startedAt,
                                ),
                            )
                            return@withMutex
                        } catch (t: Throwable) {
                            tagged.e(t) { "factory threw; treating as Retry" }
                            emitter.emit(
                                MonitorEvent.AttemptFailed(
                                    taskId = taskId,
                                    at = Clock.System.now(),
                                    attempt = attempt,
                                    reason = AttemptFailureReason.FactoryThrew(t),
                                ),
                            )
                            applyResult(task, taskId, attempt, WorkResult.Retry, guard)
                            emitter.emit(
                                MonitorEvent.WorkCompleted(
                                    taskId = taskId,
                                    at = Clock.System.now(),
                                    attempt = attempt,
                                    result = WorkResult.Retry,
                                    runtime = Clock.System.now() - startedAt,
                                ),
                            )
                            return@withMutex
                        }

                    val ctx =
                        WorkerContext(
                            taskId = taskId,
                            attempt = attempt,
                            input = input,
                            capabilities = capabilities,
                        )

                    // ── Reachability gate (review-loop round 2). Closes the
                    // iOS gap where `BGAppRefreshTaskRequest` ignores the
                    // network-required field entirely and `BGProcessingTaskRequest`
                    // only treats it as advisory. The gate waits up to
                    // `min(5s, capabilities.maxExecutionTime / 4)` for the
                    // network requirement to be satisfied; on timeout we
                    // short-circuit to `WorkResult.Retry` so the scheduler's
                    // backoff path reschedules — exactly the contract the
                    // existing periodic/one-shot paths already document.
                    val gateBudget = gateBudgetFor(capabilities)
                    val gateResult = gate.awaitReachable(networkRequired, gateBudget)
                    if (gateResult is ReachabilityGate.GateResult.TimedOut) {
                        tagged.i { "reachability gate timed out (requirement=$networkRequired, budget=$gateBudget); deferring as Retry" }
                        emitter.emit(
                            MonitorEvent.AttemptDeferred(
                                taskId = taskId,
                                at = Clock.System.now(),
                                attempt = attempt,
                                reason =
                                    DeferralReason.ReachabilityTimeout(
                                        requirement = networkRequired,
                                        budget = gateBudget,
                                    ),
                            ),
                        )
                        applyResult(task, taskId, attempt, WorkResult.Retry, guard)
                        emitter.emit(
                            MonitorEvent.WorkCompleted(
                                taskId = taskId,
                                at = Clock.System.now(),
                                attempt = attempt,
                                result = WorkResult.Retry,
                                runtime = Clock.System.now() - startedAt,
                            ),
                        )
                        return@withMutex
                    }

                    var workerThrew: Throwable? = null
                    val result: WorkResult =
                        try {
                            worker.execute(ctx)
                        } catch (e: CancellationException) {
                            tagged.i { "cancelled (probably expiration): ${e.message}" }
                            throw e
                        } catch (t: Throwable) {
                            tagged.e(t) { "execute() threw; treating as Retry" }
                            workerThrew = t
                            WorkResult.Retry
                        }

                    workerThrew?.let { cause ->
                        emitter.emit(
                            MonitorEvent.AttemptFailed(
                                taskId = taskId,
                                at = Clock.System.now(),
                                attempt = attempt,
                                reason = AttemptFailureReason.WorkerThrew(cause),
                            ),
                        )
                    }

                    applyResult(task, taskId, attempt, result, guard)
                    emitter.emit(
                        MonitorEvent.WorkCompleted(
                            taskId = taskId,
                            at = Clock.System.now(),
                            attempt = attempt,
                            result = result,
                            runtime = Clock.System.now() - startedAt,
                        ),
                    )
                }
            }

        // C-1: the cancellation/expiration fallback also goes through the guard.
        // Without the guard, an expiration arriving after `applyResult` already
        // called `setTaskCompletedWithSuccess(true)` would call it a second time
        // with `false`, tripping Apple's "completed twice" assertion (and racing
        // a successful run into a reported failure).
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                guard.runOnce { task.setTaskCompletedWithSuccess(false) }
                val expiredAt = Clock.System.now()
                emitter.emit(
                    MonitorEvent.AttemptFailed(
                        taskId = taskId,
                        at = expiredAt,
                        attempt = attempt,
                        reason = AttemptFailureReason.ExpiredByOS,
                    ),
                )
                emitter.emit(
                    MonitorEvent.WorkCompleted(
                        taskId = taskId,
                        at = expiredAt,
                        attempt = attempt,
                        result = WorkResult.Failure(cause.message ?: "expired"),
                        runtime = expiredAt - startedAt,
                    ),
                )
            }
        }
    }

    /**
     * Cancel the bridge's scope. Call from app teardown / Koin module close
     * to honour CLAUDE.md §3 ("every CoroutineScope has a clear owner with a
     * defined cancellation lifecycle"). Mirrors
     * [com.happycodelucky.backgrounder.macos.NSBackgroundActivityBackedScheduler.shutdown].
     *
     * After [shutdown], in-flight workers will observe a [CancellationException];
     * the [CompletionGuard] in their [BGTask] handler ensures the iOS-level task
     * still gets exactly one `setTaskCompletedWithSuccess(false)` call.
     */
    fun shutdown() {
        log.i { "shutdown: cancelling Backgrounder.iOS scope" }
        scope.cancel(CancellationException("IOSCoroutineBridge.shutdown"))
    }

    private fun capabilitiesFor(task: BGTask): PlatformCapabilities =
        when (task) {
            // BGAppRefreshTask gets ~30 seconds. Other BGTask subclasses (BGProcessingTask)
            // get "several minutes" — we report a conservative 5-minute hint.
            is BGAppRefreshTask -> {
                PlatformCapabilities(
                    maxExecutionTime = 30.seconds,
                    cancelsInFlight = false,
                )
            }

            else -> {
                PlatformCapabilities(
                    maxExecutionTime = 5.minutes,
                    cancelsInFlight = false,
                )
            }
        }
}
