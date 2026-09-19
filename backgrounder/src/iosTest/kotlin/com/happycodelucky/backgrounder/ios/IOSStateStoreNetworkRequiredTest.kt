package com.happycodelucky.backgrounder.ios

import com.happycodelucky.backgrounder.NetworkRequirement
import com.happycodelucky.backgrounder.WorkInput
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Schema-bump tests for [IOSStateStore]'s `networkRequired` field (review-loop
 * round 2, network-gate plan §iOS state-store schema impact).
 *
 * The schema went from v1 → v2 to add the per-task `network_required` slot
 * that drives the reachability gate. v1-shaped persisted state must continue
 * to deserialize cleanly — readers default missing keys to
 * `NetworkRequirement.None`, so tasks scheduled by an older app version
 * dispatch immediately without ever consulting the gate. This avoids a
 * schema-version sweep / migration in production.
 */
class IOSStateStoreNetworkRequiredTest {
    private val taskId = "com.happycodelucky.backgrounder.test.network"

    /**
     * v1 schema (no `network_required` key): manually populate a `MapSettings`
     * with the v1 key shape and confirm the new reader returns the safe
     * default. This is the migration path — no sweep, no version branch in
     * code, just a defaulting reader.
     */
    @Test
    fun missingKeyDefaultsToNone() {
        val settings = MapSettings()
        // Write a v1-shaped record without the network_required key. We only
        // need the keys our readers actually probe; the schema_version is set
        // to 1 to simulate persisted state from before this PR.
        val base = "tasks.$taskId."
        settings.putInt("${base}schema_version", 1)
        settings.putString("${base}kind", "oneshot")
        settings.putBoolean("${base}active", true)
        settings.putString("${base}input", WorkInput.empty().toJson())
        settings.putInt("${base}attempt", 0)
        settings.putLong("${base}next_run_epoch_ms", 1_700_000_000_000L)
        // Deliberately do NOT write `${base}network_required`.

        val store = IOSStateStore(settings)
        assertEquals(NetworkRequirement.None, store.readNetworkRequired(taskId))
    }

    @Test
    fun writeAndReadRoundTripsEnumName() {
        val settings = MapSettings()
        val store = IOSStateStore(settings)

        // Exercise every NetworkRequirement variant. Token stored is the
        // enum's `name` so future renames break the round-trip — but the
        // reader gracefully defaults to None on any unknown token (see
        // unknownTokenDefaultsToNone), so the worst case is "tasks lose
        // their gate", not "tasks crash".
        for (requirement in NetworkRequirement.entries) {
            store.writeOnSchedule(
                taskId = taskId,
                kind = IOSStateStore.Kind.OneShot,
                input = WorkInput.empty(),
                ephemeral = false,
                intervalMs = null,
                nextRunEpochMs = 1L,
                networkRequired = requirement,
            )
            assertEquals(requirement, store.readNetworkRequired(taskId), "round-trip for $requirement")
        }
    }

    @Test
    fun unknownTokenDefaultsToNone() {
        // Future-proofing: if a newer version writes a token we don't
        // understand (e.g. a hypothetical NetworkRequirement.Cellular added
        // in v3 that we haven't deployed yet), the v2 reader must default
        // to None rather than crash. This is the dual of the missing-key
        // case — both produce the same safe fallback.
        val settings = MapSettings()
        val base = "tasks.$taskId."
        settings.putString("${base}network_required", "Cellular") // not in NetworkRequirement.entries
        val store = IOSStateStore(settings)
        assertEquals(NetworkRequirement.None, store.readNetworkRequired(taskId))
    }

    @Test
    fun clearRemovesNetworkRequiredKey() {
        // The schema bump added the key to the clear() remove list. Confirm
        // clear() actually removes it — otherwise a task that was once
        // scheduled with Unmetered, then cancelled, then re-scheduled with
        // None, would inherit the stale Unmetered requirement.
        val settings = MapSettings()
        val store = IOSStateStore(settings)
        store.writeOnSchedule(
            taskId = taskId,
            kind = IOSStateStore.Kind.OneShot,
            input = WorkInput.empty(),
            ephemeral = false,
            intervalMs = null,
            nextRunEpochMs = 1L,
            networkRequired = NetworkRequirement.Unmetered,
        )
        assertEquals(NetworkRequirement.Unmetered, store.readNetworkRequired(taskId))

        store.clear(taskId)
        assertEquals(
            NetworkRequirement.None,
            store.readNetworkRequired(taskId),
            "cleared task must default back to None (key removed from settings)",
        )
    }
}
