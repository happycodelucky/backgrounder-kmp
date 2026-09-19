# Task ids

A task id is a plain `String`. It names a unit of background work across `register`, `schedule`, `runNow`, `cancel`, and the inspector API, and on iOS it is the string you list in `BGTaskSchedulerPermittedIdentifiers`.

```kotlin
class SyncWorker(private val repo: Repository) : BackgroundWorker {
    override suspend fun execute(context: WorkerContext): WorkResult = ...

    companion object {
        @BGTaskSchedulerPermittedIdentifier const val ID = "dev.example.app.sync"
    }
}

backgrounder.register(SyncWorker.ID) { SyncWorker(repo = graph.repository) }
backgrounder.schedule(WorkRequest.OneTime(taskId = SyncWorker.ID))
```

Declare each id once as a `const val` next to the worker that owns it, and reference that constant everywhere else. `@BGTaskSchedulerPermittedIdentifier` marks the ids that belong in the iOS `BGTaskSchedulerPermittedIdentifiers` array (the tick and any one-shot id) so the [Gradle plugin](../recipes/ios-permitted-identifiers.md) can write them into `Info.plist`; it's an iOS-only concern, not required for background work in general. The id is a stable key: it is persisted with scheduled work and must survive app upgrades unchanged.

## The convention: reverse-DNS

Use your app's bundle or application id as a prefix, then a short verb or noun for the work:

```
dev.example.app.sync
dev.example.app.upload
dev.example.app.background-tick
```

Two reasons, both about iOS:

- Apple recommends reverse-DNS for `BGTaskScheduler` identifiers, and the sample plists in Apple's documentation use that shape. Following it keeps your `Info.plist` unsurprising to anyone who has done iOS background work before.
- Every library in the app registers into the same `BGTaskScheduler` namespace. A prefixed id can't collide with another SDK's `"sync"`.

This is a convention, not a rule. Nothing in the library or the platforms checks the shape.

## What is enforced

Two rules, applied at every public entry point that accepts an id (`register`, `WorkRequest` construction, `runNow`, and the iOS `tickIdentifier`):

- The id must be non-blank with no leading or trailing whitespace.
- The id must not contain control characters. The library persists id lists joined by an ASCII unit separator, so an id containing one would corrupt its own record.

Violations throw `IllegalArgumentException` from Kotlin and `throws` from Swift. This catches the two mistakes that fail silently at runtime otherwise: an empty id, which iOS never fires, and a copy-pasted id with a stray space that no longer matches its plist entry.

Validation runs at the entry points rather than in a wrapper type so Swift callers, who hand the library a raw string, get the same check as Kotlin callers.

## Case and characters

Ids are compared byte-for-byte on every platform. `Dev.Example.App.Sync` and `dev.example.app.sync` are two different tasks. Stick to lowercase ASCII with `.`, `-`, and `_` unless you have a reason not to.

## What can go wrong

- **Mismatch with `Info.plist`.** The id in code and the id in `BGTaskSchedulerPermittedIdentifiers` differ by a character. iOS never fires the task and logs nothing useful. The library logs an error at `start()` for the tick identifier and a warning for each registered id missing from the plist. See [iOS](../platforms/ios.md).
- **Renaming an id with work in flight.** Persisted schedules are keyed by id. Renaming orphans the old entries until they expire or are cancelled. Cancel under the old id before shipping the rename.
- **Reusing the tick identifier as a worker id.** The iOS tick is library-owned and dispatches periodics. Registering a worker under the same string makes the two compete for one `BGTaskScheduler` registration. Give the tick its own id.
