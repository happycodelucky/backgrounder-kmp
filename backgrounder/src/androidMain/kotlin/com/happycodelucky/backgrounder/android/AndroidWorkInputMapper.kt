package com.happycodelucky.backgrounder.android

import androidx.work.Data
import com.happycodelucky.backgrounder.BackoffPolicy
import com.happycodelucky.backgrounder.WorkInput

/**
 * Round-trip [WorkInput] ↔ [Data].
 *
 * We don't try to project each [WorkValue] subtype onto Android's [Data] schema
 * one-by-one. Instead the entire [WorkInput] is JSON-serialised and stored
 * under a single string key. Reasons:
 *
 * 1. [Data]'s primitive set is narrower than [WorkValue]'s and missing
 *    [WorkValue.BytesValue] coverage in some Android versions; JSON is
 *    uniform.
 * 2. The size cap is enforced at [WorkInput.of] time using the same
 *    serializer, so we don't risk "fits one shape, doesn't fit the other."
 *    Belt-and-braces: we also validate the *combined* [Data] size in [toData]
 *    against [Data.MAX_DATA_BYTES] (also 10240) to catch any payload whose
 *    JSON sits at the per-WorkInput cap and would push the total over once
 *    metadata keys are added (review-loop round 1, finding H-1).
 * 3. [Data] also gets the task id alongside, since [RegistryDispatchWorker]
 *    is the single bridge worker class — it must read the task id from
 *    `inputData` to know which factory to invoke.
 */
internal object AndroidWorkInputMapper {
    internal const val KEY_TASK_ID: String = "_backgrounder.task_id"
    internal const val KEY_INPUT_JSON: String = "_backgrounder.input_json"
    internal const val KEY_EPHEMERAL: String = "_backgrounder.ephemeral"
    internal const val KEY_MAX_ATTEMPTS: String = "_backgrounder.max_attempts"

    /**
     * Instant-dispatch payload. The runNow path needs only the task id —
     * `PendingInstantCalls` (in-process map) holds the lambda and the
     * deferred. WorkManager re-uses this slim payload across both [Data] keys.
     */
    fun toInstantData(taskId: String): Data =
        Data
            .Builder()
            .putString(KEY_TASK_ID, taskId)
            .build()

    /**
     * Build the [Data] payload for a scheduled task.
     *
     * @throws IllegalArgumentException if the combined serialised size of every
     *   key/value pair exceeds [Data.MAX_DATA_BYTES] (10240). The error message
     *   names the actual byte size and mentions the cap so callers can shrink
     *   their [WorkInput] payload. Without this guard, the failure would surface
     *   at `WorkManager.enqueueUniqueWork` time as a generic
     *   `IllegalStateException` from `Data.Builder.build()`.
     */
    fun toData(
        taskId: String,
        input: WorkInput,
        ephemeral: Boolean,
        maxAttempts: Int,
    ): Data =
        try {
            Data
                .Builder()
                .putString(KEY_TASK_ID, taskId)
                .putString(KEY_INPUT_JSON, input.toJson())
                .putBoolean(KEY_EPHEMERAL, ephemeral)
                .putInt(KEY_MAX_ATTEMPTS, maxAttempts)
                .build()
        } catch (e: IllegalStateException) {
            // `Data.Builder.build()` throws IllegalStateException with the message
            // "Data cannot occupy more than 10240 bytes when serialized" when the
            // combined serialised form (JSON + metadata key strings + Int + Boolean)
            // exceeds Data.MAX_DATA_BYTES. We translate to IllegalArgumentException
            // because the failure is caused by caller-supplied input — not a
            // programmer error inside the library — and a clear message lets the
            // caller shrink their WorkInput before reaching WorkManager.enqueue.
            throw IllegalArgumentException(
                "Backgrounder Data payload exceeds WorkManager's ${Data.MAX_DATA_BYTES}-byte cap. " +
                    "Reduce your WorkInput payload — remember the JSON cap " +
                    "(${WorkInput.MAX_SERIALIZED_BYTES}) is for the WorkInput alone; metadata keys " +
                    "(task id, ephemeral, max-attempts) consume additional bytes.",
                e,
            )
        }

    fun readTaskId(data: Data): String? = data.getString(KEY_TASK_ID)

    fun readInput(data: Data): WorkInput {
        val json = data.getString(KEY_INPUT_JSON) ?: return WorkInput.empty()
        return WorkInput.fromJson(json)
    }

    fun readEphemeral(data: Data): Boolean = data.getBoolean(KEY_EPHEMERAL, false)

    /**
     * Read the per-cycle retry cap from a persisted [Data] payload.
     *
     * Defaults to [BackoffPolicy.DEFAULT_MAX_ATTEMPTS] (10) when the key is
     * absent — this happens for [Data] blobs persisted by an older version of
     * the library that didn't write the key. The previous default of
     * `Int.MAX_VALUE` silently disabled the cap mechanism for upgraded apps,
     * letting workers retry indefinitely (review-loop round 1, finding H-2).
     */
    fun readMaxAttempts(data: Data): Int = data.getInt(KEY_MAX_ATTEMPTS, BackoffPolicy.DEFAULT_MAX_ATTEMPTS)
}
