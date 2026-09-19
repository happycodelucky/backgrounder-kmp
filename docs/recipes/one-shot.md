# Schedule a one-shot

A one-shot job runs once. It survives process death and reboot (unless `ephemeral = true`), and is retried per `BackoffPolicy.maxAttempts` if the worker returns `WorkResult.Retry`.

```kotlin
import com.happycodelucky.backgrounder.*
import kotlin.time.Duration.Companion.seconds

val outcome = backgrounder.schedule(
    WorkRequest.OneTime(
        taskId = SyncWorker.ID,
        constraints = WorkConstraints(
            networkRequired = NetworkRequirement.Any,
            requiresCharging = false,
        ),
        initialDelay = 30.seconds,
        backoff = BackoffPolicy.exponential(
            initialDelay = 30.seconds,
            maxAttempts = 5,
        ),
    ),
)

when (outcome) {
    ScheduleOutcome.Scheduled -> Unit
    is ScheduleOutcome.Rejected -> log.e { "rejected: ${outcome.reason}" }
}
```

## What can go wrong

- **iOS Info.plist** — every task id you schedule must appear in `BGTaskSchedulerPermittedIdentifiers`. The library logs an error during `backgrounder.start()` if it's missing; rejected at `schedule()` time with `ScheduleOutcome.Rejected`.
- **Constraints conflict on iOS** — `NetworkRequirement.Unmetered` is honoured by the library's pre-execution reachability gate (`isDataMetered == false`), but the OS-level scheduling hint (`BGProcessingTaskRequest.requiresNetworkConnectivity`) is downgraded to `Any` with a log warning — iOS's BGTaskScheduler has no metered/unmetered distinction at the dispatch level.
- **Backoff policy with too-small initial delay** — minimum is 10 seconds, validated at construction.

## Conflict policy

If a one-shot with the same task id is already pending:

```kotlin
backgrounder.schedule(request, policy = ConflictPolicy.Replace) // default — cancel pending, enqueue new
backgrounder.schedule(request, policy = ConflictPolicy.Keep)    // ignore new, keep pending
```

`Append` (Android chained-work semantics) is v2.

!!! note "Periodic `Replace` on Android is an in-place update"
    For **periodic** work on Android, `Replace` maps to WorkManager's `ExistingPeriodicWorkPolicy.UPDATE`: the existing request's interval, constraints, and input are patched in place, preserving run history and the next-run anchor. It is *not* a cancel-and-re-enqueue — the cadence does not reset. One-shot `Replace` keeps the cancel-and-re-enqueue semantics (`ExistingWorkPolicy.REPLACE`).
