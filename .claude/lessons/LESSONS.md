# Lessons Learned — Backgrounder

Living document. Agents and humans add entries here whenever something is worth
remembering across sessions. Read it before planning non-trivial work and
whenever you get stuck — we may have seen the issue before.

## How to use this file

- **Before planning** a non-trivial change: skim all four sections, then grep
  for keywords from the task (e.g. `mutex`, `BGTask`, `XCFramework`,
  `SKIE`, `WorkManager`).
- **When stuck** for more than a few minutes: search here before going wider.
- **Add an entry as soon as you learn something** — don't batch. A terse line
  written now beats a polished paragraph never written.

## How to add an entry

- Pick the right section. If it fits two, pick the one a future reader would
  search first.
- Allocate the next sequential ID for that section (`B-007`, `D-003`, …). IDs
  are stable forever — never renumber.
- One to three lines per entry. Cite a file path or commit/PR when useful.
  No long prose. If you need more than three lines, you're explaining what,
  not why.
- Code comments may reference an entry by ID (e.g. `// see B-004`).
- If an entry becomes obsolete, mark it `~~B-NNN~~ (superseded by B-NNN)` —
  do not delete. History matters.

Date format: `YYYY-MM-DD`. Always absolute, never relative ("last week").

---

## Bugs we've hit (B)

Bugs introduced in this codebase that are worth remembering. Each entry:
**cause → fix**, plus the file or PR if known.

The `H1`–`M6` review catalogue in
[`.claude/agents/kmp-pro.md`](../agents/kmp-pro.md) is the kmp-pro agent's
review checklist. Entries below are the wider set of bugs that have actually
landed in this repo — review-catalogue items are listed here as well so a
single grep finds them.

### B-028 — Release failed on `compileCommonMainKotlinMetadata`; CI `check` never runs it — 2026-09-19
**Cause:** `LibraryScopeInstantRunner` imported `kotlin.coroutines.cancellation.CancellationException` and passed it to `Job.cancel` / `CoroutineScope.cancel`, which take `kotlinx.coroutines.CancellationException`. Per-target compiles accept it (both alias the same platform class) but the metadata compile treats them as distinct expect classes. `check` doesn't run metadata compilation; only publishing does, so it surfaced in the release workflow.
**Fix:** Import `kotlinx.coroutines.CancellationException` in commonMain whenever the value is handed to a coroutines API. `mise run check` (what CI runs) now includes `compileKotlinMetadata`.

### B-001 — iOS retry backoff off-by-one — 2026-04
**Cause:** Retry paths called `delayFor(attempt + 1)` / `delayFor(nextAttempt)`, doubling the first-retry wait vs Android.
**Fix:** Pass the attempt that *just failed* — `delayFor(attempt)`. `delayFor(0)` is the pre-first-retry wait; `nextAttempt` is only for the persisted counter and `shouldGiveUp(...)`.
**Ref:** commit `24db731`. Test `BackoffPolicyTest.firstRetryWaitsInitialDelay`.

### B-002 — `IOSTaskMutexes` per-task map leak — 2026-04
**Cause:** `MutableMap<TaskId, Mutex>` only ever grew; entries never removed on terminal paths.
**Fix:** `forget(taskId)` / `forgetAll()` under the same `synchronized` block, called from cancel, cancelAll, one-shot terminal branches, give-up, unknown-kind. Do not drop on periodic-active branches.
**Ref:** commit `24db731`. Test seam: `trackedCount()`.

### B-003 — `NSBackgroundActivityScheduler` tolerance > interval — 2026-04
**Cause:** Floored `interval` and `tolerance` at `1.0`, producing `tolerance > interval` on one-shots (Foundation: undefined behaviour).
**Fix:** Allow `interval = 0` for one-shots; cap `tolerance` with `coerceAtMost(interval)`. Apply for periodics too.
**Ref:** commit `24db731`.

### B-004 — `BGTaskScheduler.submitTaskRequest(error = null)` is a hard crash — 2026-04
**Cause:** Passing `null` for the `NSError**` out-pointer raises `NSException`; K/N `try/catch` does NOT catch ObjC exceptions.
**Fix:** Always allocate a real `ObjCObjectVar<NSError?>` via `memScoped` and surface failures as `BGSubmitResult.Failure`. Helper: `submitBGTaskRequest`.
**Ref:** commit `24db731`.

### B-005 — `EphemeralRegistry` read-modify-write race — 2026-04
**Cause:** `add { snapshot().toMutableSet().add(item); write(...) }` without a lock; `NSUserDefaults` / `SharedPreferences` are write-atomic, not RMW-atomic.
**Fix:** Wrap every RMW in `kotlinx.atomicfu.locks.synchronized` against a `SynchronizedObject`. Single-flag state → `kotlinx.atomicfu.atomic`.
**Ref:** commit `24db731`. Test `concurrentAddsDoNotLoseEntries` fans 64 concurrent adds.

### B-006 — `BGTask` double-completion / wrong-outcome race — 2026-04
**Cause:** Both `applyResult` (success) and the iOS expiration handler (cancel fallback) could call `setTaskCompletedWithSuccess(_:)`. Race → Apple "BGTask completed twice" assertion or success reported as failure.
**Fix:** Per-invocation `CompletionGuard` (single-fire atomicfu latch in `appleMain`); every completion site is `guard.runOnce { ... }`.
**Ref:** PR #5, commit `67fa08d`. See [`backgrounder/src/appleMain/.../CompletionGuard.kt`](../../backgrounder/src/appleMain/kotlin/com/happycodelucky/backgrounder/CompletionGuard.kt).

### B-007 — `IOSCoroutineBridge` scope had no cancellation path — 2026-04
**Cause:** `SupervisorJob`-rooted scope created in a Koin `single`, never cancelled — violates CLAUDE.md §3 "every scope has a clear owner".
**Fix:** `IOSCoroutineBridge.shutdown()` (mirrors macOS); exposed via public `Backgrounder.shutdown()`. Call from `applicationWillTerminate` / test `tearDown`.
**Ref:** PR #5, commit `67fa08d`.

### B-008 — `cancelAll` skipped `setActive(false)` ordering — 2026-04
**Cause:** Single `cancel()` did `cancelTaskRequestWithIdentifier → setActive(false) → clear`. `cancelAll()` skipped the middle step; a handler firing between cancel and clear saw `active=true` and resubmitted.
**Fix:** Mirror the single-cancel ordering inside the `cancelAll` `forEach`.
**Ref:** PR #5, commit `67fa08d`.

### B-009 — Expiration handler registered *after* `scope.launch` — 2026-04
**Cause:** iOS can fire expiration any time after the handler closure returns; an unset expiration handler at that moment crashes.
**Fix:** Register the expiration handler **first**, capturing a `var job: Job? = null` slot. Apple guarantees expiration only fires once the BGTask handler closure has returned — by then `job` is assigned.
**Ref:** PR #5, commit `67fa08d`.

### B-010 — macOS one-shot `Retry` was a silent no-op — 2026-04
**Cause:** Returning `WorkResult.Retry` from a one-shot called `completion(NSBackgroundActivityResultDeferred)`. `Deferred` is only meaningful when `repeats=true`; for one-shots it's a drop. Library lied about `supportsRetryBackoff`.
**Fix:** `handleOneShotRetry(...)` invalidates the current activity and schedules a fresh `repeats=false` activity with `interval = backoff.delayFor(attempt)`, honouring `maxAttempts`.
**Ref:** PR #7, commit `393421b`.

### B-011 — iOS `Keep` policy ran side effects before early-return — 2026-04
**Cause:** `schedule()` added to `ephemeral` and fired `onScheduled` *before* the `Keep && active` early-return — metrics drifted, ephemeral state corrupted on no-op reschedule.
**Fix:** Probe `state.readActive(...)` first; only on a real schedule do we mutate `ephemeral` and fire `onScheduled`. Same bug & fix in macOS `NSBackgroundActivityBackedScheduler`.
**Ref:** PR #7, commit `393421b`. 2026-06-10: the macOS "same fix" covered only the early-return path — the lock-recheck path regressed and was re-fixed; see B-022.

### B-012 — Android `cancel()` always returned `Cancelled(1)` — 2026-04
**Cause:** No process-local id tracking; cancel of an unknown id reported success and fired `onCancelled` regardless.
**Fix:** `ScheduledIdsTracker` (a `MutableSet<TaskId>` under `kotlinx.atomicfu.locks.synchronized`). `cancel` returns `NoSuchTask` when absent. Still calls `cancelUniqueWork` regardless (cross-process best effort).
**Ref:** PR #6, commit `e586cc0`.

### B-013 — Android `Data.Builder.build()` overflow surfaced late — 2026-04
**Cause:** `WorkInput.MAX_SERIALIZED_BYTES` caps the JSON payload; `Data.Builder.build()` caps the *combined* form (JSON + 4 metadata key strings + Boolean + Int). A WorkInput under the per-WorkInput cap could overflow `Data` at `enqueueUniqueWork` time, not at `schedule()` time.
**Fix:** Catch the `IllegalStateException` from `Data.Builder.build()` in the mapper; re-throw as `IllegalArgumentException` naming the cap. Surface fails at the library boundary the user can act on.
**Ref:** PR #6, commit `e586cc0`.

### B-014 — `readMaxAttempts` defaulted to `Int.MAX_VALUE` — 2026-04
**Cause:** Legacy `Data` blobs missing `KEY_MAX_ATTEMPTS` returned `Int.MAX_VALUE`; the cap check `if (attempt + 1 >= cap)` never fired — retry cap silently disabled on upgrades.
**Fix:** Default to `BackoffPolicy.DEFAULT_MAX_ATTEMPTS` (10).
**Ref:** PR #6, commit `e586cc0`.

### B-015 — `WorkInput.entries()` returned non-bridgeable `Map.Entry` — 2026-04
**Cause:** `Set<Map.Entry<String, WorkValue>>` has no usable Swift form (no exhaustive switch, no value-type semantics) — violates CLAUDE.md §8.
**Fix:** `@HiddenFromObjC` on `entries()`; add `keysSnapshot()` for Swift, callers look up values via `get(key:)`.
**Ref:** PR #8, commit `75d1407`.

### B-016 — `RegistryDispatchWorker` captured thread name at field init — 2026-04
**Cause:** `Thread.currentThread().name` read at field-init runs on the construction thread, not the work thread.
**Fix:** Capture inside `doWork()` instead.
**Ref:** commit `24db731`.

### B-017 — `AndroidEphemeralSweep` per-id `get(timeout)` on cold start — 2026-04
**Cause:** Per-item deadline produced total wall budget of `5s × N` — ANR risk if N is large.
**Fix:** Cap the **total wall budget** across all ephemeral ids at 5s.
**Ref:** commit `24db731`.

### B-018 — Release workflow Maven secrets resolved empty — 2026-05-13
**Cause:** `release.yml` referenced `environment: continous-deloyment` (typo), but the GitHub Environment is `continuous-deployment`. Actions silently tolerates the mismatch; `secrets.*` resolves to empty strings; `publishToMavenCentral` runs with blank credentials and fails.
**Fix:** Rename the workflow binding to the correct spelling. No silent tolerance from GitHub for this case.
**Ref:** PR #18, commit `c857ead`.

### B-019 — Release `patch` bump used `GITHUB_RUN_NUMBER` — 2026-05-14
**Cause:** Holdover from the no-version-base era. `bumpType=patch` produced a fresh run-number patch on every retry instead of `X.Y.Z → X.Y.Z+1`.
**Fix:** Increment the real patch component; `minor`/`major` zero components below. Re-runs now compute the same version; the duplicate guard step + tag-exists check catch accidents.
**Ref:** PR #19, commit `178c551`.

### B-020 — `IOSBackoffEmulation.nextRunEpochMs` read wall clock — 2026-04
**Cause:** Read `Clock.System.now()` directly; tests' `runTest` virtual time can't reach it — tests were lying about timing.
**Fix:** Dispatcher computes the backoff delay locally using the policy and adds to its **injected** `now` — no wall-clock read inside dispatcher math.
**Ref:** commit `fa573fa`.

### B-021 — `WorkerRegistry.create()` calls user factory inside `synchronized(lock)` — 2026-06-10
**Cause:** Both per-id (`it()`) and bulk (`owningFactory.create(taskId)`) factory calls run while holding the registry's `SynchronizedObject` lock. Note: atomicfu's `SynchronizedObject` IS reentrant on K/N (verified against `Synchronized.kt` in kotlinx-atomicfu — `ownerThreadId` + `reEnterCount`), so a same-thread callback does *not* deadlock. The real costs: every concurrent dispatch across all TaskIds serializes behind arbitrary user code (slow factory stalls everything), and a factory that blocks on another thread needing the lock deadlocks.
**Fix:** Capture the factory reference inside the lock, release the lock, then invoke the factory outside it. Landed 2026-06-10. Test: `WorkerRegistryTest.factoryCanCallBackIntoRegistryDuringCreate`.
**Ref:** `WorkerRegistry.kt::create` (identified in commonMain review 2026-06-10).

### B-022 — macOS `schedule()` fired side effects before the lock-recheck — 2026-06-10
**Cause:** The H-3/B-011 fix covered only the Keep early-return; `ephemeral.add` + the `Scheduled` emit still ran *between* the probe and the final lock-recheck. Losing the recheck produced phantom `Scheduled` events and ghost ephemeral entries. A lesson's fix must be applied to **every** probe-then-recheck site, not just the one that bit.
**Fix:** Single authoritative `synchronized` block decides + mutates (including `ephemeral`, mirroring `cancel()`); events emitted after the lock from the recorded decision.
**Ref:** `NSBackgroundActivityBackedScheduler.kt::schedule`.

### B-023 — iOS `submit` failure left a ghost `EphemeralRegistry` entry — 2026-06-10
**Cause:** `schedule()` adds the ephemeral entry before submission; the `BGSubmitResult.Failure` branch cleared `IOSStateStore` but not the ephemeral marker — `scheduled()`/`diagnostics()` reported a ghost task until the next cold-launch sweep.
**Fix:** `ephemeral.remove(taskId)` next to `state.clear(taskId)` in the failure branch. Test: `BGTaskBackedSchedulerSubmitFailureTest` (uses the new injectable `submitRequest` seam).
**Ref:** `BGTaskBackedScheduler.kt::submit`.

### B-024 — `WorkerRegistry.seal()` wrote the sealed flag outside the registry lock — 2026-06-10
**Cause:** `register()` checks `sealed` inside `synchronized(lock)` but `seal()` wrote it lock-free — a register racing `start()` could slip in after the engine began installing OS handlers (on iOS: a registered worker whose BGTask handler never gets installed).
**Fix:** `seal()` takes the same lock. One line.
**Ref:** `WorkerRegistry.kt::seal`.

### B-025 — Android `ScheduleReplaced` decided via snapshot-then-add — 2026-06-11
**Cause:** `WorkManagerScheduler.schedule()` read `scheduledIds.snapshot()` to decide the `ScheduleReplaced` emit, then called `add()` later — two racing `schedule()` calls could both miss (or both see) the prior entry and emit inconsistent replace events.
**Fix:** `ScheduledIdsTracker.addAndWasPresent()` — one guarded read-modify-write. Test: `ScheduledIdsTrackerTest.addAndWasPresentReportsPriorTrackingInOneOperation`.
**Ref:** `WorkManagerScheduler.kt::schedule`, `ScheduledIdsTracker.kt`.

### B-026 — `docs/concepts/ephemeral.md` promised WorkManager retry after the ready-gate bail — 2026-06-11
**Cause:** The doc claimed a gated ephemeral dispatch "will pick the work up again on the next dispatch". The code returns a terminal `Result.failure()` — there is no retry; the sweep purges. Docs drifted from the D-020 contract.
**Fix:** Doc now states the purge/no-retry contract; the misleading `RegistryDispatchWorker` log line ("retry will happen after init completes") fixed in the same pass.
**Ref:** `docs/concepts/ephemeral.md`, `RegistryDispatchWorker.kt`.

### B-027 — macOS `SchedulerGuarantees` claimed schedules survive process death / reboot / force-quit — 2026-06-11
**Cause:** `MACOS_GUARANTEES` shipped `survivesProcessDeath/Reboot/ForceQuit = true`. `NSBackgroundActivityScheduler` is in-process — registered activities die with the process and nothing relaunches the app. The public `guarantees()` API (and the docs table mirroring it) misadvertised durability that doesn't exist.
**Fix:** All three flags `false`, with the in-process rationale at the declaration; `docs/concepts/guarantees.md` table corrected.
**Ref:** `NSBackgroundActivityBackedScheduler.kt::MACOS_GUARANTEES`.

---

## Novel design decisions (D)

Creative or non-obvious decisions the team made — especially ones a fresh
reader would not infer from the code. Capture the **decision** and the
**reason it beat the obvious alternative**.

### D-001 — DI-free Backgrounder (drop Koin entirely) — 2026-05-10
**Decision:** Tore out Koin (BOM + core + android + workmanager + test). Single constructed `Backgrounder` per app; constructor injection threads dependencies through per-platform builders.
**Why over the obvious alternative:** Koin was a global service locator we didn't actually need — the forward-reference puzzle it solved (lambda capturing not-yet-built object) dissolves with explicit construction order. DI is a user choice; the library must not impose a container. Replaced `koin-androidx-workmanager` with a 3-line hand-rolled `BackgrounderWorkerFactory`.
**Ref:** PR #10, commit `9e1f4f3`.

### D-002 — iOS library-owned tick identifier replaces per-TaskId periodic registration — 2026-05-10
**Decision:** Periodics no longer get per-TaskId `BGProcessingTaskRequest`s. The library registers a **single** consumer-supplied `BGAppRefreshTaskRequest` identifier and an in-process foreground feed; `IOSPeriodicDispatcher` coalesces both paths.
**Why over the obvious alternative:** Per-TaskId registration had two gaps: (a) `BGAppRefreshTaskRequest` doesn't fire while the app is foregrounded, (b) iOS can't coalesce multiple due periodics — picks one identifier, the rest wait indefinitely. The dispatcher pattern advances `nextRunEpochMs` **before** the worker runs so concurrent dispatches coalesce by TaskId. Cost: one consumer-supplied tick identifier in Info.plist `BGTaskSchedulerPermittedIdentifiers`.
**Ref:** PR #12, commit `fa573fa`. See [`backgrounder/src/iosMain/.../ios/IOSPeriodicDispatcher.kt`](../../backgrounder/src/iosMain/kotlin/com/happycodelucky/backgrounder/ios/IOSPeriodicDispatcher.kt).

### D-003 — Library-managed network gate, not WorkerContext.network plumbing — 2026-05-11
**Decision:** A `ReachabilityGate` in commonMain wraps `Reachability.shared` from `com.happycodelucky.reachable`; iOS/macOS schedulers call `gate.awaitReachable(requirement, budget)` before invoking the worker. Wait window: `min(5.seconds, budget / 4)`. Timeout → `WorkResult.Retry`.
**Why over the obvious alternative:** A `WorkerContext.network` API would proxy a singleton the worker can already read. Workers needing soft network policy read `Reachability.shared` themselves inside `execute()`. CLAUDE.md §5 "DI is a user choice" — don't proxy upstream.
**Ref:** PR #13 / #14 / #15, commits `dfd4bba`, `417b8be`, `89b63fb`. Files: [`ReachabilityGate.kt`](../../backgrounder/src/commonMain/kotlin/com/happycodelucky/backgrounder/ReachabilityGate.kt).

### D-004 — Sealed result types over `kotlin.Result<T>` at the Swift boundary — 2026-04
**Decision:** Public Swift-facing APIs return project-defined `sealed interface`s (`WorkResult`, `ScheduleOutcome`, `CancelOutcome`, `FetchUserOutcome`-style). Internal `runCatching` is fine.
**Why over the obvious alternative:** SKIE doesn't bridge `kotlin.Result<T>` to Swift `Result<Success, Failure>` — generic erasure differs, `Failure: Error` constraint isn't satisfied. What Swift sees is opaque `KotlinResult`: no exhaustive switch, no `try?`. Sealed types render via SKIE as exhaustive Swift `enum`s with structured payloads — *richer* than two-case `Result`. Same fix for `Pair`/`Triple` at the boundary.
**Ref:** CLAUDE.md §8; PR #4 elevates the rule, commit `4d9e293`.

### D-005 — Periodic missed cycles fire once on next wake (no catch-up) — 2026-05-10
**Decision:** `WorkRequest.Periodic` documents that N missed cycles fire **once** on the next wake — never N times back-to-back. Workers needing catch-up compute it from their own persisted state.
**Why over the obvious alternative:** Apple's BGAppRefresh, Android's WorkManager periodic, and our foreground feed all converge on "fire once when due"; emulating catch-up would either thrash on resume or require shared persistence beyond the library's remit. Contract holds across all three platforms.
**Ref:** PR #12, commit `fa573fa`. Documented in `docs/recipes/periodic.md`.

### D-006 — `AndroidScheduledTaskMapper` projection (`WorkInfoView`) instead of mocking `WorkInfo` — 2026-04
**Decision:** Extract the WorkInfo→ScheduledTask transform behind a `WorkInfoView` data class. Tests construct `WorkInfoView` directly; no Robolectric, no 13-parameter `WorkInfo` constructor that drifts across androidx.work minor versions.
**Why over the obvious alternative:** Robolectric or Mockito for a pure data transform is dead weight. Projection isolates what we actually consume (tags, state, attempts, nextScheduleTimeMillis) from the framework type's churn.
**Ref:** PR #9, commit `035ac15`. See `AndroidScheduledTaskMapperTest`.

### D-007 — Directory / Gradle module / Maven artifact id all match — 2026-05-13
**Decision:** Renamed `/shared` → `/backgrounder`; module path `:backgrounder`; published artifact `com.happycodelucky.backgrounder:backgrounder`. Triplet matches.
**Why over the obvious alternative:** `:shared` as a coordinate adds noise once published. Mirrors Ktor / kotlinx / sibling `com.happycodelucky.reachable:reachable`. Done via `git mv` so blame follows.
**Ref:** PR #16, commit `08887c8`.

### D-008 — Maven Central via vanniktech; SPM is local-only — 2026-05-13
**Decision:** Active distribution = Maven Central (`com.happycodelucky.backgrounder:backgrounder`) via vanniktech `maven-publish`. Pure-Swift consumers in this repo use the root `Package.swift` with `.binaryTarget(path:)` pointing at the debug XCFramework that `mise run spm:dev` rebuilds. Remote SPM (hosted zip) is future work.
**Why over the obvious alternative:** KMM consumers (the majority) resolve transparently through `mavenCentral()`. Pure-Swift remote SPM via KMMBridge was sketched but never wired — held back rather than half-shipping.
**Ref:** PR #17, commit `c1dc264`. CLAUDE.md §9.

### D-009 — `automaticRelease = false` in `mavenPublishing { }` is load-bearing — 2026-05-13
**Decision:** Keep `publishToMavenCentral(automaticRelease = false)` so `dryRun=true` workflow runs upload to Central Portal **staging** and stop. Runner reviews + clicks Publish in the Portal UI.
**Why over the obvious alternative:** Flipping it to `true` silently turns every dry run into an irreversible publish. Releases are uncancellable once auto-released.
**Ref:** PR #17, commit `c1dc264`. CLAUDE.md §9 release pipeline.

### D-010 — Promote scheduling verbs onto `Backgrounder`; hide `Scheduler` interface — 2026-05-14
**Decision:** Removed public `Backgrounder.scheduler` property and `Scheduler` interface. `schedule`/`cancel`/`cancelAll`/`scheduled`/`guarantees` are now methods on `Backgrounder` itself. Scheduler becomes an internal seam (FakeScheduler still uses it).
**Why over the obvious alternative:** One facade is easier to thread through an app graph than two coupled handles. No deprecation path needed — no external consumers yet.
**Ref:** PR #22, commit `7f01ea4`.

### D-011 — `BackgroundWorkerFactory` for bulk registration — 2026-05-14
**Decision:** Add a factory interface so consumers can register one factory for many TaskIds (lazy `create()`), as an alternative to per-id closure registration. Resolution order: per-id first, then factories in registration order. Declared id sets must not overlap; overlap throws.
**Why over the obvious alternative:** Per-id closures are great for small apps but force eager construction. Factories let big graphs lazy-resolve. A factory declaring an id but returning `null` from `create()` throws `FactoryDeclinedException` — declared-but-declined is a client sync bug, not a fall-through.
**Ref:** PR #21, commit `215a19d`.

### D-012 — Apple platform-name casing rule overrides Kotlin acronym convention — 2026-04
**Decision:** `iOS`, `macOS`, `tvOS`, `watchOS`, `iPadOS`, `visionOS` keep their brand casing in identifiers we author (`IOSCoroutineBridge`, not `IosCoroutineBridge`). Standard Kotlin `HtmlParser`-style camel-casing does NOT apply.
**Why over the obvious alternative:** They're trademark spellings; readability across iOS/macOS developer audiences matters more than within-Kotlin consistency. JetBrains-supplied identifiers (`iosMain`, `iosArm64`, `withIos()`) and package segments stay lowercase — we don't fight the tool.
**Ref:** CLAUDE.md §3; commit `247417a`.

### D-013 — Library version literal lives in `build.gradle.kts`; CI never writes back — 2026-04
**Decision:** `allprojects { version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT") }`. Humans bump major/minor in the literal; CI stamps `-Pversion=...`. CI never commits a version change.
**Why over the obvious alternative:** Version-as-property avoids commit-loops on every release. Mirrors appletv-client / reachable.
**Ref:** commit `41a2e23`.

### D-014 — Mise is the canonical build entry; gradle is the second-level — 2026-05-13
**Decision:** `mise.toml` pins JDK, Python, Gradle bootstrap, gh. Contributor docs lead with `mise run check / test / xcframework / docs:*`; raw `./gradlew` shown as power-user fallback. CI is mise-driven.
**Why over the obvious alternative:** Without `mise install`, contributors pick up whatever JDK the runner image / their laptop happens to ship. Mise pins it to match CI exactly.
**Ref:** PR #16 / #17, commits `08887c8`, `0e2ebe2`.

### D-015 — `setup-gradle@v5`, not `v6` — 2026-05-13
**Decision:** Pin `gradle/actions/setup-gradle@v5` in CI workflows.
**Why over the obvious alternative:** v6 moved caching to a commercially-licensed component. v5 keeps the freely-licensed cache engine and runs on Node 24. Rationale is in-file in the workflows.
**Ref:** PR #16, commit `08887c8`.

### D-016 — Docs site uses `mkdocs-macros` for version injection — 2026-05-14
**Decision:** `main.py` (mkdocs-macros entry point) resolves `version` from `BACKGROUNDER_VERSION` env → latest GitHub release tag → `main` fallback. `docs/installation.md` uses `{{ version }}` placeholders. README pins manually (it renders raw on GitHub, can't use the macro).
**Why over the obvious alternative:** Hand-edited version strings rot. Release workflow calls `docs.yml` via `workflow_call` after publish so the site re-resolves immediately.
**Ref:** PR #20, commit `2ecee6a`.

### D-017 — `mkdocs build --strict` + `docs/check.py` gate the docs — 2026-04
**Decision:** mkdocs runs strict (broken links / nav drift fail the build). `docs/check.py` asserts every `.md` is in `nav:` and every recipe/platform page has at least one fenced code block.
**Why over the obvious alternative:** Strict mode catches what humans miss; `check.py` catches what mkdocs doesn't (stub pages with no content).
**Ref:** commit `08f522e`.

### D-018 — `expect`/`actual` cap ~20 lines, otherwise refactor to interface — 2026-04
**Decision:** Codified in CLAUDE.md §4. If an `actual` is more than ~20 lines, refactor to a `commonMain` interface with platform implementations injected at the entrypoint.
**Why over the obvious alternative:** Large `expect`/`actual` surfaces hide architecture. Interfaces declare contract explicitly and survive refactors better.
**Ref:** CLAUDE.md §4.

### D-019 — `MonitorEventEmitter` is `tryEmit`-only — 2026-05-17
**Decision:** Every emit site funnels through `MonitorEventEmitter.emit(...)`, which fans out synchronously to the legacy `BackgrounderEventListener` and `tryEmit`s into the public `SharedFlow(replay=0, extraBufferCapacity=64, onBufferOverflow=DROP_OLDEST)`. Never the suspending `MutableSharedFlow.emit`.
**Why over the obvious alternative:** Suspending `emit` lets a slow collector backpressure-block the producer. The iOS bridge holds the per-task `Mutex` while emitting; a slow `events()` collector would serialise every subsequent dispatch on that id. CLAUDE.md §3 forbids that. `DROP_OLDEST` is honest — collectors see the gap rather than the producer stalling.
**Ref:** PR #28 (wave 1).

### D-020 — Process-death contract: ephemeral work is purged, never retried — 2026-06-11
**Decision:** On every platform, work that fires before the library is ready (process restarted, factories not yet registered) terminates with a failure; ephemeral entries are purged by the cold-start sweep. No `retry()` path exists for the not-ready case — ephemeral or not.
**Why over the obvious alternative:** `Result.retry()` would resurrect work the app may no longer define (factory removed across an upgrade), pollute WorkManager's backoff counters with failures that aren't the worker's fault, and contradict the `ephemeral` flag's whole premise — "do not run from a state I didn't deliberately put it in". The app re-schedules from its own init path.
**Ref:** owner decision 2026-06-11. `RegistryDispatchWorker.kt`, `docs/concepts/ephemeral.md`. See B-026.

### D-021 — `Throwable` payloads carry bridged `causeMessage` / `causeType` strings — 2026-06-11
**Decision:** Public event payloads that carry a `Throwable` (`MonitorEvent.LibraryError`, `AttemptFailureReason.FactoryThrew` / `WorkerThrew`) keep the raw `cause` for Kotlin consumers but `@HiddenFromObjC` it, exposing `causeMessage: String?` + `causeType: String?` (computed defaults) for Swift.
**Why over the obvious alternative:** Dropping `cause` entirely punishes Kotlin consumers (loses stack traces); leaving it visible gives Swift an opaque `KotlinThrowable` (N-009 root cause). Hidden-plus-bridged-strings serves both. Residual wart: `copy()` / `componentN()` still mention the Kotlin type — acceptable, consumers don't construct events.
**Ref:** `MonitorEvent.kt`; pattern follows B-015 (`@HiddenFromObjC` + Swift-friendly alternative).

### D-022 — Process death: honour platform resilience, never imitate it — 2026-06-11
**Decision:** Refines D-020 beyond ephemeral. In-flight work may try to complete. On **Android**, WorkManager's durability is honoured: a worker whose process died mid-run may be re-dispatched by the OS and complete — the library does NOT suppress it (an `InFlightMarkers` suppression mechanism was built, then deliberately reverted same-day on owner direction: "if Android can complete work it should"). On **iOS/macOS**, where no platform resilience exists, the library adds none — a run that died with its process is never replayed; iOS additionally clears dead one-shot state at `start()` (`IOSOneShotReconciliation`, snapshot-first) so the `active=true` ghost can't permanently block Keep-policy re-schedules. Scheduled-but-never-started work is *not* interrupted and runs normally where the platform persists it.
**Why over the obvious alternative:** A uniform "never restart anywhere" contract required fighting WorkManager's core durability model — discarding real resilience to buy cross-platform sameness nobody benefits from. Honouring each platform's native semantics costs one doc paragraph; imitating resilience on Apple platforms (or suppressing it on Android) costs correctness machinery and surprises platform-native developers.
**Ref:** owner decisions 2026-06-11 (both directions — see git history of PR #30 for the built-then-reverted suppression). Test: `IOSOneShotReconciliationTest`. Docs: `docs/concepts/guarantees.md` § Process death.

### D-023 — README banner: self-contained SVG pair, evergreen subtitle — 2026-06-11
**Decision:** The README hero is `docs/assets/banner-{light,dark}.svg` — logo shapes *inlined* (GitHub's camo proxy blocks external refs and webfonts inside `<img>`-rendered SVGs), text via GitHub's own system-font stack, theme-switched with the `<picture>`/`prefers-color-scheme` pattern. The baked-in subtitle is "One Kotlin Multiplatform API for background work." — deliberately no platform roster, no badges, no versions, so the image never goes stale (wasm may join the targets).
**Why over the obvious alternative:** A PNG banner can't theme-switch palettes cleanly, bloats the repo, and blurs on retina; referencing `logo-*.svg` from inside the banner renders blank on GitHub. Mutable facts (platforms, versions, CI state) stay in markdown badges/text where they're cheap to edit.
**Ref:** `docs/assets/banner-light.svg`, `docs/assets/banner-dark.svg`, README header.

### D-024 — JVM target: pure coroutine scheduler; desktop instant runner promoted to commonMain — 2026-06-11
**Decision:** The `jvm()` target schedules with library-owned coroutines (`CoroutineBackedScheduler`: one lazy `Job` per request, `delay`-driven, injected dispatcher + clock per N-011) — there is no OS primitive to delegate to. `LibraryScopeInstantRunner` moved macosMain → commonMain with a `platformLabel` param (pure Kotlin; macOS + JVM share it, avoiding the B-011/B-022 duplicate-drift trap). Guarantees mirror macOS: all survival flags false. Terminal one-shots clear their own tracking (jobs/attempts/ephemeral) — `scheduled()` reports only live work.
**Why over the obvious alternative:** Wrapping `java.util.Timer`/`ScheduledExecutorService` adds a thread pool the coroutine runtime already provides, and loses virtual-time testability. The jvm target requires reachable ≥ the first jvm-enabled release (0.13.0 has no jvm artifact); pin note in libs.versions.toml.
**Ref:** `backgrounder/src/jvmMain/`, `CoroutineBackedSchedulerTest` (exact-timing virtual-clock suite).

### D-025 — `requiresDeviceIdle`: enforced on Android, advisory + log-once elsewhere — 2026-06-20
**Decision:** `WorkConstraints.requiresDeviceIdle` (the "device-idle" item previously parked as a v2 gap in `WorkConstraints` KDoc) ships as a real `Constraints.Builder.setRequiresDeviceIdle` gate on Android (JobScheduler / Doze), and as a **no-op** on iOS / macOS / JVM. `BGProcessingTaskRequest` has no idle property (only `requiresExternalPower` + `requiresNetworkConnectivity`); macOS/JVM have no idle primitive. iOS emits a one-time `log.w` in `applyConstraints` (mirroring the `NetworkRequirement.Unmetered` warning); macOS/JVM stay silent-with-a-comment because that's how `requiresCharging` is already treated in those files — match the file's local convention, don't invent a log it doesn't have. Inspector parity: added `PendingPredicate.RequiresDeviceIdle` + Android `WorkInfo` read-back, mirroring `RequiresCharging` at every touch-point (field → mapper → predicate → snapshot data class). This is the **advisory-constraint template** for the next cross-platform constraint: enforce where native, accept-and-document elsewhere, never imitate.
**Why over the obvious alternative:** A library-managed idle *gate* (like the reachability gate) was rejected — there is no portable "is the device idle?" signal to poll, and idle is an OS-internal fuzzy notion; faking it would be wrong more often than right. Honest advisory + the existing per-platform "check inside `execute()` and return Retry" escape hatch costs one doc paragraph and zero correctness machinery. No `minInterval`/throttle was built (owner descoped it the same session) — opportunistic dispatch already floors frequency.
**Ref:** `WorkConstraints.kt`, `AndroidConstraintsMapper.kt`, `AndroidScheduledTaskMapper.kt`, `PendingPredicate.kt`, iOS `BGTaskBackedScheduler.applyConstraints`. Docs: `docs/concepts/opportunistic-dispatch.md` (new), `docs/recipes/network-required.md` § "Require the device to be idle".

### D-026 — Task ids are plain `String`s; reverse-DNS is a documented convention, not a rule — 2026-09-18
**Decision:** Removed the `TaskId` value class. Ids are `String` everywhere; the only checks (non-blank, no surrounding whitespace, no control characters) run in `requireValidTaskId` at each public entry point.
**Why over the obvious alternative:** K/N exports a value class as its underlying type, so Swift never saw `TaskId` and its `init` validation never ran for Swift callers — the wrapper was Kotlin-only ceremony with a false sense of safety. Neither `BGTaskScheduler` nor WorkManager constrains id shape. Entry-point validation covers both languages once. Pre-release, so no compat cost.

### D-027 — Task-id collection is a bytecode scan in a Gradle plugin, not KSP or a compiler plugin — 2026-09-18
**Decision:** `:backgrounder-gradle-plugin` reads `@BGTaskSchedulerPermittedIdentifier const val` fields from the module's compiled JVM classes with ASM and rewrites the iOS plist array; no code generation.
**Why over the obvious alternative:** KSP can't read property initializers (only annotation args), so "annotate the val" is impossible there; a compiler plugin reads literals but locks consumers to an exact Kotlin version. `const val` folds into bytecode, so a scan sees the real value (including `"$PREFIX.x"` concatenations) with a stable, Kotlin-version-independent reader. Plugin locates the compile task by name to avoid linking KGP.
Named after Apple's plist key (not `@BackgroundTaskId`) so nobody reads it as required for all background work — it's iOS-only and covers only the tick + one-shot ids.
**Ref:** `backgrounder-gradle-plugin/`, `docs/recipes/ios-permitted-identifiers.md`.

### D-028 — One live `Backgrounder` per process, exposed as `Backgrounder.shared`; explicit construction kept — 2026-09-19
**Decision:** `SharedBackgrounder` slot in commonMain; platform builders install into it, `shutdown()` releases it, a second live instance throws. Android populates it via an `androidx.startup` initializer (manifest-registered); iOS/macOS/JVM create lazily on first access. `create`/`configure` stay for listeners and custom tick ids.
**Why over the obvious alternative:** WorkManager and `BGTaskScheduler.shared` are process singletons — the instance-keyed map in `AndroidBackgrounderInternals` existed only to route reflective Worker construction back to "the" instance. A lazily-created global that self-configures can't work on Android (needs `Application`), so construction stays explicit there, automated by startup. Swift can't see a Kotlin companion `val shared` (K/N already emits `Companion.shared`), so the property is `@HiddenFromObjC` and a SKIE-bundled Swift extension (`src/appleMain/swift`) provides `Backgrounder.shared` over `sharedInstance()`.
**Ref:** `SharedBackgrounder.kt`, `BackgrounderShared.kt`, `BackgrounderInitializer.kt`, `src/appleMain/swift/BackgrounderShared.swift`.

### D-029 — Ephemeral sweep: snapshot at construction, cancel at `start()` — 2026-09-19
**Decision:** Every platform's sweep captures `ephemeral.snapshot()` when the instance is built and cancels exactly those ids in `start()`, removing only them (not `clear()`).
**Why over the obvious alternative:** Android construction can now run from the startup initializer, before `Application.onCreate`; touching `WorkManager` there locks in the default `Configuration` before `Configuration.Provider` runs. Sweeping at `start()` with a fresh snapshot would instead cancel any ephemeral request the app scheduled between construction and `start()`. Snapshot-then-remove closes both holes.
**Ref:** `AndroidEphemeralSweep.kt`, `IOSEphemeralSweep.kt`, `MacOSBackgrounderBuilder.kt`, `JvmBackgrounderBuilder.kt`.

### D-030 — Entry class is `BackgroundTaskManager`; `Backgrounder` is the library/module name only — 2026-09-19
**Decision:** Renamed `class Backgrounder` → `BackgroundTaskManager`. Files, tests, and the shared-slot object follow (`SharedBackgroundTaskManager`). Library-branded siblings keep `Backgrounder*` names.
**Why over the obvious alternative:** Renaming the framework module (`BackgrounderKit`) would have kept `Backgrounder.shared` in Swift but touched KMMBridge, `Package.swift`, and every consumer's `import`. The class rename removes the collision at the source, reads better (`BackgroundTaskManager.shared` says what it is), and keeps the brand on the module where it belongs.
**Ref:** `BackgroundTaskManager.kt`, T-009.

---

## NEVER DO (N)

Things we have explicitly tried, undone, or declared off-limits in this
project — beyond CLAUDE.md §13's general hard rules. Section here is for the
**contextual** never-dos: the ones a fresh reader would not infer.

### N-001 — Never include `CancellationException` in `@Throws` on SKIE-bridged APIs — 2026-04
**Don't:** `@Throws(CancellationException::class)` on `suspend fun` consumed from Swift.
**Why:** SKIE bridges `suspend fun` as Swift `async throws` and routes cancellation through Swift's native `Task.cancel()` / `CancellationError`. Adding `CancellationException` pollutes the generated signature and forces consumers to write `catch is CancellationError` — meaningless. The legacy pattern is correct for direct K/N ObjC export only; this repo uses SKIE. Flag existing call sites for cleanup.
**Ref:** CLAUDE.md §8; PR #8 swept the existing sites, commit `75d1407`.

### N-002 — Never call `KoinPlatform.getKoin()` without `OrNull` — 2026-04
**Don't:** `runCatching { KoinPlatform.getKoin() }`.
**Why:** Koin has `getKoinOrNull()` for exactly this. The `runCatching` wrap is louder and lies about intent (we're not handling an exception — we're checking initialization).
**Ref:** kmp-pro anti-patterns; relevant to legacy code only — Koin is now gone (see D-001).

### N-003 — Never call `suspend` functions inside a `kotlinx.atomicfu.locks.synchronized` block — 2026-04
**Don't:** Suspend inside a `synchronized(lockObject) { ... }`.
**Why:** The non-suspending two-tier lock is for short critical sections. If the body needs to suspend, you wanted `kotlinx.coroutines.sync.Mutex.withLock { ... }`. Lock declarations carry a `// MUST NOT call suspend functions inside this block` comment.
**Ref:** CLAUDE.md §3, §13. Pattern shown in `IOSTaskMutexes`.

### N-004 — Never add CocoaPods, Compose Multiplatform, x86/x86_64, watchOS, or tvOS — 2026-04
**Don't:** Touch any of the above.
**Why:** Out of scope. Targets are ARM-only by policy (CLAUDE.md §1, §13). CocoaPods is forbidden as a distribution channel; SKIE + Maven Central + local SPM is the path (CLAUDE.md §9, D-008).
**Ref:** CLAUDE.md §1, §9, §13.

### N-005 — Never use `as? List<SomeT>` with `@Suppress("UNCHECKED_CAST")` — 2026-04
**Don't:** Suppress unchecked casts on lists at the K/N boundary.
**Why:** K/N erases element types — the cast says nothing about element correctness, and a wrong-typed entry crashes deep in the call stack. Use `(it as? List<*>)?.filterIsInstance<SomeT>()` instead.
**Ref:** kmp-pro anti-patterns; commit `24db731` swept two such sites.

### N-006 — Never pin a dependency you don't wire in — 2026-05-13
**Don't:** Leave a version in `gradle/libs.versions.toml` for a plugin/library that isn't actually applied.
**Why:** KMMBridge was pinned at 1.2.1 but never wired into any module's `plugins { }` block. Pinning gave the false impression it was on the build path and produced dead docs. Either wire it or remove it.
**Ref:** PR #17, commit `c1dc264`.

### N-007 — Never use `kotlin.synchronized`, `@Synchronized`, `java.util.concurrent.locks.*`, or `volatile` — 2026-04
**Don't:** Reach for any JVM-only lock primitive.
**Why:** Not portable to K/N or wasm. Only `kotlinx.atomicfu.locks.synchronized` (non-suspending) and `kotlinx.coroutines.sync.Mutex` (suspending) are KMP-portable. `kotlin.synchronized` is JVM-only despite the package name.
**Ref:** CLAUDE.md §3, §13.

### N-008 — Never edit the workflow's environment name to "fix" the typo without coordinating — 2026-05-13
**Don't:** Rename `continuous-deployment` ↔ any variant without updating both the GitHub Environment name *and* every workflow that binds to it, in lockstep.
**Why:** Actions silently tolerates a non-matching environment name — secrets resolve empty, the job runs, the publish fails with cryptic auth errors. B-018 burned us once.
**Ref:** PR #18, commit `c857ead`.

### N-009 — Never use `kotlin.Result<T>` / `Pair<…>` / `Triple<…>` in public Swift-facing signatures — 2026-04
**Don't:** Return or expose them on public declarations in `commonMain` / `appleMain` / `iosMain` / `macosMain`.
**Why:** SKIE has no special-case bridge; Swift sees opaque generic wrappers with no exhaustive switch and no value-type semantics. Use a sealed interface (D-004) or a named `data class`. Internal `runCatching` is fine — the rule is about the **public** boundary.
**Ref:** CLAUDE.md §8; PR #4, commit `4d9e293`.

### N-010 — Never bump Kotlin past SKIE's supported range — 2026-04
**Don't:** Bump Kotlin without checking the SKIE compatibility matrix first.
**Why:** SKIE lags Kotlin releases by a few days. Bumping past the supported range disables SKIE; the framework falls back to default K/N ObjC export and the Swift surface regresses dramatically.
**Ref:** CLAUDE.md §2, §8.
**Lockstep example (2026-06):** reachable 0.13.0 was built on Kotlin 2.3.21, so the consumer bump pulled Kotlin 2.3.20→2.3.21 — which required SKIE 0.10.11→0.10.12 (its sole change is "Support for Kotlin 2.3.21"). Kotlin and SKIE moved together; this is the rule working as intended, not an exception to it.

### N-011 — Never read wall-clock time inside dispatcher / scheduler logic — 2026-05-10
**Don't:** Call `Clock.System.now()` from code under test that uses `runTest` virtual time.
**Why:** `runTest` can't intercept the wall-clock read; tests silently lie about timing. Inject a `now: () -> Long` and add deltas to that. See B-020.
**Ref:** PR #12, commit `fa573fa`. 2026-06-11: remaining violations swept — `IOSBackoffEmulation` is now pure math over a `nowMs` param, `BGTaskBackedScheduler` takes an injected `clock`, `IOSStateStore.recordRun` requires `nowMs`. The one documented exemption: `epochMsToNSDate` (FFI boundary to an absolute-time OS API). Test: `IOSSchedulerVirtualClockTest`.

### N-012 — Never add a `WorkerContext.network` (or similar) proxy for `Reachability.shared` — 2026-05-12
**Don't:** Wrap upstream singletons in thin library-side proxies.
**Why:** Workers needing soft network policy can read `Reachability.shared` directly inside `execute()`. The library only gates on `WorkConstraints.networkRequired`; finer-grained logic is the worker's job. See D-003.
**Ref:** PR #15, commit `89b63fb`.

### N-013 — Never call ObjC/Foundation scheduling APIs while holding a Kotlin-side lock — 2026-06-10
**Don't:** Call `scheduleWithBlock`, `submitTaskRequest`, or any Foundation/BackgroundTasks dispatch inside a `kotlinx.atomicfu.locks.synchronized` block.
**Why:** Foundation takes internal locks of its own, and its callbacks re-enter Kotlin code that acquires ours — a lock-order cycle we can neither see nor control. Mutate the Kotlin-side maps under the lock, release, then make the ObjC call; a concurrent cancel/replace invalidates the activity first, making the late call a harmless no-op.
**Ref:** `NSBackgroundActivityBackedScheduler.kt` (`schedule` / `handleOneShotRetry`), 2026-06-10 review.

---

## Troubleshooting (T)

When we got stuck, what unstuck us. Symptom-first so a future reader can
match against what they're seeing.

### T-001 — Maven publish fails with blank credentials — 2026-05-13
**Symptom:** `publishToMavenCentral` reaches the upload step but the request has empty `Authorization`. Run logs show the env step succeeding.
**Cause:** Workflow `environment:` binding doesn't match the actual GitHub Environment name (typo). Actions tolerates the mismatch silently → secrets resolve to empty strings.
**Unstuck by:** Match the binding to the Environment name exactly. Check via the repo's Settings → Environments. See B-018.
**Ref:** PR #18.

### T-002 — `assembleBackgrounderXCFramework` fails on reachable's bundled `Reachability+Shared.swift` — 2026-05-12
**Symptom:** XCFramework build fails with namespacing conflict around `BackgrounderReachableReachability`.
**Cause:** Reachable 0.9.0 shipped a `.swift` file inside its klib that conflicted with transitive consumption from a SKIE-enabled framework.
**Unstuck by:** Bump to reachable 0.11.10 (no `.swift` sources in the klib). Companion change: drop the `@HiddenFromObjC` overload-split on `Backgrounder.{ios,macos}.kt::create(...)`; tests use upstream `withFakeReachability { ... }` instead of an injection seam.
**Ref:** PR #14, commit `417b8be`.

### T-003 — Dispatcher tests pass but production behaviour drifts — 2026-05-10
**Symptom:** `runTest` tests verify backoff timing perfectly; manual runs show different delays.
**Cause:** A helper (`IOSBackoffEmulation.nextRunEpochMs`) read `Clock.System.now()` directly — `runTest` virtual time can't reach it. Tests were measuring the policy curve, not the dispatcher's actual `now` advancement.
**Unstuck by:** Dispatcher computes the delay locally using the policy and adds to its injected `now`. No wall-clock read in dispatcher math. See B-020.
**Ref:** PR #12.

### T-004 — `BGTask` registered but never fires in the simulator — 2026-05-10
**Symptom:** App registers a BGTask identifier; no fire on the device or simulator after the expected interval.
**Cause:** Several possibilities — most commonly (a) the identifier is missing from `Info.plist` `BGTaskSchedulerPermittedIdentifiers`, (b) the consumer-supplied tick identifier mismatches the Info.plist entry, (c) the user has force-quit the app (see `docs/platforms/force-quit.md`).
**Unstuck by:** Use the LLDB `e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"..."]` recipe in `docs/recipes/test-workers.md`. `Backgrounder.start()` logs a Kermit error if the Info.plist entry is missing.
**Ref:** `docs/platforms/force-quit.md`, `docs/recipes/test-workers.md`.

### T-005 — Android `WorkInfo` test constructor breaks on minor `androidx.work` bumps — 2026-04
**Symptom:** `AndroidScheduledTaskMapperTest` fails to compile after an androidx.work bump; `WorkInfo` constructor signature changed.
**Cause:** `WorkInfo` has 13+ constructor parameters and the order shifts across minor versions. Tests that build a `WorkInfo` directly break frequently.
**Unstuck by:** Use the `WorkInfoView` projection — tests construct the projection, production maps `WorkInfo → WorkInfoView` once at the boundary. See D-006.
**Ref:** PR #9, commit `035ac15`.

### T-007 — Gradle fails at configuration with "SDK location not found" in a fresh worktree — 2026-06-10
**Symptom:** Any `./gradlew` task dies in ~2s: `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or … local.properties`.
**Cause:** `local.properties` is gitignored, so git worktrees (including `.claude/worktrees/*`) don't inherit it from the main checkout.
**Unstuck by:** `cp <main-checkout>/local.properties <worktree>/local.properties` (SDK lives at `~/Library/Android/sdk`). Also: a piped `./gradlew … | tail` reports the *pipe's* exit code — check `BUILD SUCCESSFUL`/`FAILED` in the log, not just `$?`.

### T-009 — Swift sees the `Backgrounder` class as `Backgrounder_` — 2026-09-19
**Symptom:** Bundled Swift (`src/appleMain/swift`) fails with "cannot find type 'Backgrounder' in scope / cannot use module as a type"; SKIE warns at link time that `class Backgrounder` was renamed to `Backgrounder_` "because of a name collision with the framework name".
**Cause:** The XCFramework module and the Kotlin class share the name `Backgrounder`. SKIE's apinotes rename the class for Swift, so real Swift consumers write `Backgrounder_`, not the `Backgrounder.companion.create(...)` the docs show.
**Unstuck by:** Renamed the class to `BackgroundTaskManager` (D-030); the framework stays `Backgrounder`. Bundled Swift now writes `public extension BackgroundTaskManager` with no qualification. Rule for the future: never give a public type the framework's name.

### T-008 — Flow collector in runTest's backgroundScope misses the final emission — 2026-06-11
**Symptom:** A test collecting `Backgrounder.events()` into a list via `backgroundScope.launch { flow.collect { … } }` asserts on the *last* event emitted before idle — and the list is missing exactly that event, intermittently by test shape.
**Cause:** `advanceUntilIdle()` stops when *foreground* work is idle; the background collector's final resumption can still be queued at current virtual time.
**Unstuck by:** `runCurrent()` after the last `advanceUntilIdle()` — it flushes current-time tasks regardless of foreground/background. See `CoroutineBackedSchedulerTest.noFactoryRegisteredSkipsStructurallyWithoutRetry`.

### T-006 — Pre-flight check says version is already on Maven Central, but the local literal looks newer — 2026-05-14
**Symptom:** Release workflow `Verify version not already published` step fails on a version that should be brand new.
**Cause:** Likely a previous **non-dry-run** publish or a leftover staged Portal deployment that was auto-released. The Central Portal is the source of truth.
**Unstuck by:** Check `central.sonatype.com` → Deployments. If a stuck deployment exists, Drop it. Then bump the version literal in `build.gradle.kts` (humans bump major/minor; the workflow bumps patch). See D-009.
**Ref:** PR #19.
