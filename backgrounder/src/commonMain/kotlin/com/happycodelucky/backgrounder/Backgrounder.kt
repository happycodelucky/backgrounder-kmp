package com.happycodelucky.backgrounder

import kotlinx.coroutines.flow.SharedFlow
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Constructed-instance entry point — held by the user's app graph for
 * the app's lifetime.
 *
 * Three things hang off the instance:
 *  - scheduling verbs ([schedule], [cancel], [cancelAll], [scheduled],
 *    [guarantees]): the scheduling surface, promoted directly onto the
 *    instance. There is no separate `Scheduler` object to hold — pass the
 *    `Backgrounder` instance itself down the app graph.
 *  - [register]: associate a task id with a factory closure that builds a
 *    fresh `BackgroundWorker` per dispatch.
 *  - [start]: finalize init (seals the registry; iOS/macOS run the ephemeral
 *    sweep + register OS handlers + resurrect periodic schedules; Android
 *    flips the not-ready backstop). Idempotent.
 *  - [shutdown]: tear down library-owned coroutine scopes (iOS / macOS).
 *    Android is a no-op. Safe to call repeatedly.
 *
 * Construct via the per-platform extension factory:
 *   - `androidMain`: [Backgrounder.Companion.create] taking an `Application`.
 *   - `iosMain`: [Backgrounder.Companion.create] (no required args).
 *   - `macosMain`:   [Backgrounder.Companion.create] (no required args).
 *   - `jvmMain`: [Backgrounder.Companion.create] (no required args).
 *
 * `@OptIn(ExperimentalObjCName::class)`: standard SKIE annotation; stable in
 * practice and required for boundary refinement (CLAUDE.md §8).
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "Backgrounder")
public class Backgrounder internal constructor(
    private val engine: BackgrounderEngine,
) {
    /**
     * Register a [BackgroundWorker] factory for [taskId]. Must be called
     * before [start]. Throws if [start] has already run or [taskId] is
     * already registered.
     *
     * The factory closes over the user's DI graph (Koin, Hilt, hand-wired,
     * any of them — the library doesn't care). Each dispatch invokes the
     * factory afresh; workers are never cached.
     *
     * @throws IllegalStateException if [start] has already run.
     * @throws IllegalArgumentException if [taskId] is already registered.
     */
    @ObjCName(swiftName = "register")
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    public fun register(
        taskId: String,
        factory: () -> BackgroundWorker,
    ) {
        engine.registry.register(taskId, factory)
    }

    /**
     * Register a [BackgroundWorkerFactory] that owns many task ids at once.
     * Must be called before [start]. Throws if [start] has already run, or
     * any of the factory's [BackgroundWorkerFactory.taskIds] collide with an
     * existing per-id registration or another factory.
     *
     * This is the bulk alternative to per-id [register] — one factory object,
     * many ids, lazy worker resolution. See [BackgroundWorkerFactory] for the
     * `taskIds` / `create` sync contract.
     *
     * @throws IllegalStateException if [start] has already run.
     * @throws IllegalArgumentException if any of the factory's ids is already registered.
     */
    @ObjCName(swiftName = "register")
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    public fun register(factory: BackgroundWorkerFactory) {
        engine.registry.register(factory)
    }

    /**
     * Finalize initialization. After this call:
     *   - the registry is sealed; further [register] calls throw;
     *   - iOS / macOS perform the ephemeral sweep and register OS handlers;
     *   - Android clears the not-ready backstop so workers that fire from
     *     this point onwards may dispatch.
     *
     * Idempotent — repeated calls are no-ops. Call exactly once at app launch.
     */
    @ObjCName(swiftName = "start")
    public fun start() {
        engine.start()
    }

    /**
     * Schedule a [WorkRequest]. If a request with the same [WorkRequest.taskId]
     * is already pending, [policy] decides what happens.
     *
     * Platform-backed: `WorkManager` on Android, `BGTaskScheduler` on iOS,
     * `NSBackgroundActivityScheduler` on macOS.
     */
    @ObjCName(swiftName = "schedule")
    public fun schedule(
        request: WorkRequest,
        policy: ConflictPolicy = ConflictPolicy.Replace,
    ): ScheduleOutcome = engine.scheduler.schedule(request, policy)

    /**
     * Cancel every pending scheduled request the library knows about.
     *
     * Does not interrupt already-running workers on iOS — see
     * [SchedulerGuarantees.cancelsInFlight]. To also cancel in-flight
     * [runNow] calls for a single id, use [cancel].
     */
    @ObjCName(swiftName = "cancelAll")
    public fun cancelAll(): CancelOutcome = engine.scheduler.cancelAll()

    /**
     * Snapshot of currently-scheduled (pending or running) tasks the library
     * knows about. Best-effort per platform.
     *
     * No `@Throws` — SKIE bridges `suspend fun` as Swift `async throws` and
     * routes coroutine cancellation through Swift's native `Task.cancel` /
     * `CancellationError` machinery (CLAUDE.md §8).
     */
    @ObjCName(swiftName = "scheduled")
    public suspend fun scheduled(): List<ScheduledTask> = engine.scheduler.scheduled()

    /** What this platform's scheduler actually guarantees. */
    @ObjCName(swiftName = "guarantees")
    public fun guarantees(): SchedulerGuarantees = engine.scheduler.guarantees()

    /**
     * Every task id currently registered with the library — the union of
     * per-id closures and every [BackgroundWorkerFactory]'s declared ids.
     *
     * Snapshot; safe to call at any time, before or after [start].
     */
    @ObjCName(swiftName = "registeredTaskIds")
    public fun registeredTaskIds(): Set<String> = engine.registry.registeredIds()

    /**
     * Inspector view of every registered factory — one [FactoryDescriptor]
     * per closure registration and per [BackgroundWorkerFactory] object.
     * See [WorkerRegistry.factoryDescriptors] for ordering.
     */
    @ObjCName(swiftName = "registeredFactories")
    public fun registeredFactories(): List<FactoryDescriptor> = engine.registry.factoryDescriptors()

    /**
     * Snapshot of environment and configuration issues the library has
     * detected — missing iOS `Info.plist` entries, disabled Android
     * `WorkManager`, registry not yet sealed, etc. An empty
     * [PlatformDiagnostics.diagnostics] list (or
     * `PlatformDiagnostics.isHealthy == true`) means the library believes
     * the environment is correctly configured.
     *
     * Best-effort per platform — see [PlatformDiagnostic] for which cases
     * each platform can produce.
     */
    @ObjCName(swiftName = "diagnostics")
    public fun diagnostics(): PlatformDiagnostics = platformDiagnostics(engine.registry, engine.isStarted)

    /**
     * Hot stream of [MonitorEvent]s — every schedule, dispatch, deferral,
     * completion, retry, cancellation, and library-internal error the
     * scheduler observes.
     *
     * Swift sees this as `AsyncSequence<MonitorEvent>` via SKIE. Iterate with
     * `for await event in backgrounder.events() { switch onEnum(of: event)
     * { … } }` for exhaustive case handling.
     *
     * **Semantics.** Hot, non-replaying — late collectors do not see history.
     * Backed by a `MutableSharedFlow(replay = 0, extraBufferCapacity = 64,
     * onBufferOverflow = DROP_OLDEST)`; sustained back-pressure drops the
     * oldest unread events first. Emit is non-suspending so a slow collector
     * cannot pin scheduler dispatch (CLAUDE.md §3).
     *
     * For the imperative callback alternative covering the four v1 events
     * only, see [BackgrounderEventListener]. Both channels are fed from the
     * same internal emit point and stay in lockstep.
     */
    @ObjCName(swiftName = "events")
    public fun events(): SharedFlow<MonitorEvent> = engine.events

    /**
     * Run [task] immediately under [taskId] and suspend until it completes,
     * returning the typed result `R`.
     *
     * **Semantics — "raw" background dispatch.** Unlike scheduled work
     * ([schedule] + [register]), `runNow`:
     *  - runs *immediately*, with no [WorkConstraints], no [BackoffPolicy], no
     *    retries, no [ExecutionHint] gating;
     *  - does **not** consult [WorkerRegistry] — the [task] lambda *is* the work,
     *    and [taskId] does **not** need to be `register()`-ed first;
     *  - is routed through the OS scheduling primitive on Android (`WorkManager`)
     *    and iOS (`BGTaskScheduler`) so the work earns background runtime if
     *    the app is suspended mid-call; on macOS and the JVM it runs on a
     *    library-owned `SupervisorJob` scope (`NSBackgroundActivityScheduler`
     *    is interval-shaped and a poor fit for one-shot work; the JVM has no
     *    platform scheduler at all).
     *
     * **Pre-emption — "last call wins".** If a `runNow` is already in flight
     * for [taskId], or a scheduled run for [taskId] is pending or executing,
     * `runNow` cancels them all (via [cancel]) *before* submitting its own
     * request. The prior caller's `await` rethrows `CancellationException`.
     * This is necessary because `runNow` returns a typed result to a specific
     * caller — two concurrent runs for the same id would be ambiguous.
     *
     * **Cancellation.** Caller cancellation flows through structured concurrency:
     * the OS request is cancelled (best-effort on iOS — `BGTaskScheduler`
     * cannot kill a *running* handler, only pending requests; an in-flight
     * lambda is cancelled via the in-process bridge `Job`), the `task` lambda
     * observes `CancellationException`, and `runNow` rethrows.
     *
     * **Exceptions.** A `Throwable` thrown by [task] propagates to the caller's
     * `await`. The platform layer reports `WorkResult.Failure` to the OS so
     * it doesn't treat the process as crashed. SKIE bridges this as Swift
     * `async throws -> R`.
     *
     * **iOS `Info.plist` requirement.** [taskId] *must* appear in the
     * app's `BGTaskSchedulerPermittedIdentifiers` array. If it does not,
     * `BGTaskScheduler.submit` rejects the request and `runNow` throws an
     * `IllegalStateException` whose message names the missing identifier.
     *
     * @throws IllegalStateException if [start] has not been called yet, or
     *   the platform refuses the request (iOS only — see above).
     */
    @ObjCName(swiftName = "run")
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    public suspend fun <R> runNow(
        taskId: String,
        task: suspend () -> R,
    ): R {
        requireValidTaskId(taskId)
        check(engine.isStarted) {
            "Backgrounder.runNow($taskId): start() has not been called yet."
        }
        // Pre-empt anything else for this id (in-flight runNow, pending schedule,
        // in-flight scheduled worker). The prior runNow caller — if any — sees
        // CancellationException from their await.
        cancel(taskId)
        return engine.instantRunner.run(taskId, task)
    }

    /**
     * Cancel everything the library knows about for [taskId]:
     *  - any pending scheduled request;
     *  - any in-flight scheduled worker (best-effort per platform);
     *  - any in-flight [runNow] call (its `Deferred` completes with
     *    `CancellationException`, the caller's `await` rethrows).
     *
     * Returns `Cancelled(pendingCleared)` where `pendingCleared` is the
     * platform-reported count of pending scheduled requests removed
     * (best-effort — Android reports an accurate count; iOS reports 0 or 1).
     * `runNow` cancellations are **not** added to `pendingCleared` — that
     * field's meaning is "platform-pending requests removed" and we keep it
     * stable.
     *
     * Returns `NoSuchTask` only when neither a scheduled request nor an
     * in-flight `runNow` existed for [taskId].
     *
     * This is the broad cancel — it covers both scheduled work and [runNow].
     * [cancelAll] cancels every pending scheduled request but does not touch
     * in-flight [runNow] calls.
     */
    @ObjCName(swiftName = "cancel")
    public fun cancel(taskId: String): CancelOutcome {
        val schedulerOutcome = engine.scheduler.cancel(taskId)
        val cancelledRunNow = engine.instantRunner.cancelInFlight(taskId)
        return when (schedulerOutcome) {
            is CancelOutcome.Cancelled -> {
                schedulerOutcome
            }

            CancelOutcome.NoSuchTask -> {
                if (cancelledRunNow) CancelOutcome.Cancelled(pendingCleared = 0) else CancelOutcome.NoSuchTask
            }
        }
    }

    /**
     * Tear down library-owned coroutine scopes (CLAUDE.md §3 — every scope has
     * a clear owner with a defined cancellation lifecycle).
     *
     * **iOS / macOS / JVM:** cancels the scope owned by the platform scheduler /
     * coroutine bridge. In-flight workers observe a `CancellationException`;
     * the per-task completion guard reports the iOS-level task as
     * `setTaskCompletedWithSuccess(false)` exactly once.
     *
     * **Android:** no-op. WorkManager owns its own dispatcher.
     *
     * Safe to call multiple times. Typically called from app teardown — e.g.
     * an iOS test's `tearDown`, or from `applicationWillTerminate` on macOS.
     */
    @ObjCName(swiftName = "shutdown")
    public fun shutdown() {
        engine.shutdown()
        // Free the process-wide slot so a fresh instance can be created.
        SharedBackgrounder.release(this)
    }

    /**
     * Companion object exists so per-platform source sets can install
     * extension entry points: `Backgrounder.shared` (commonMain),
     * `Backgrounder.configure(application)` (Android), and
     * `Backgrounder.create(...)` (iOS / macOS / JVM). `commonMain` cannot
     * define the constructors itself because the Android variant requires an
     * `Application` and the Apple variants don't — there's no common
     * signature that doesn't leak `Any?`.
     */
    public companion object
}
