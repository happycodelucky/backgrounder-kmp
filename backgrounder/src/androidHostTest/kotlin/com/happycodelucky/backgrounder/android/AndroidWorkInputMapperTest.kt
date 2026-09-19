package com.happycodelucky.backgrounder.android

import androidx.work.Data
import com.happycodelucky.backgrounder.BackoffPolicy
import com.happycodelucky.backgrounder.WorkInput
import com.happycodelucky.backgrounder.WorkValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [AndroidWorkInputMapper] — no Robolectric needed because
 * `androidx.work.Data` is a plain bag (Parcelable yes, but constructible
 * outside the Android framework).
 */
class AndroidWorkInputMapperTest {
    private val taskId = "com.happycodelucky.backgrounder.test.sync"

    @Test
    fun roundTripPreservesEverything() {
        val input =
            WorkInput.of(
                "k1" to WorkValue.StringValue("hello"),
                "k2" to WorkValue.LongValue(42L),
            )
        val data = AndroidWorkInputMapper.toData(taskId, input, ephemeral = true, maxAttempts = 7)

        assertEquals(taskId, AndroidWorkInputMapper.readTaskId(data))
        assertEquals(input, AndroidWorkInputMapper.readInput(data))
        assertTrue(AndroidWorkInputMapper.readEphemeral(data))
        assertEquals(7, AndroidWorkInputMapper.readMaxAttempts(data))
    }

    @Test
    fun emptyInputRoundTrips() {
        val data =
            AndroidWorkInputMapper.toData(
                taskId,
                WorkInput.empty(),
                ephemeral = false,
                maxAttempts = 10,
            )
        assertEquals(taskId, AndroidWorkInputMapper.readTaskId(data))
        assertEquals(WorkInput.empty(), AndroidWorkInputMapper.readInput(data))
        assertEquals(false, AndroidWorkInputMapper.readEphemeral(data))
        assertEquals(10, AndroidWorkInputMapper.readMaxAttempts(data))
    }

    @Test
    fun ephemeralDefaultsFalseAndMaxAttemptsDefaultsToBackoffPolicyDefault() {
        // Given a Data missing our keys (e.g. older library versions that didn't
        // write them), defaults must be safe — H-2: defaulting to Int.MAX_VALUE
        // silently disabled the retry cap for upgraded apps. Default is now
        // BackoffPolicy.DEFAULT_MAX_ATTEMPTS (10), which preserves the cap.
        val data = Data.EMPTY
        assertEquals(null, AndroidWorkInputMapper.readTaskId(data))
        assertEquals(WorkInput.empty(), AndroidWorkInputMapper.readInput(data))
        assertEquals(false, AndroidWorkInputMapper.readEphemeral(data))
        assertEquals(BackoffPolicy.DEFAULT_MAX_ATTEMPTS, AndroidWorkInputMapper.readMaxAttempts(data))
    }

    @Test
    fun toDataRejectsPayloadOverDataMaxBytes() {
        // H-1: WorkInput.MAX_SERIALIZED_BYTES (10240) is the cap for the JSON
        // payload alone, but Data.Builder.build() throws if the total serialised
        // form (JSON + the four metadata key/value pairs) exceeds
        // Data.MAX_DATA_BYTES (also 10240). A WorkInput whose JSON sits in the
        // narrow band "≤ 10240 bytes for ofMap, but > 10240 once metadata is
        // added" used to silently fail at WorkManager.enqueue time; we now
        // surface it as an IllegalArgumentException at toData() with a clear
        // message.
        //
        // Construct a WorkInput whose JSON is near (but under) MAX_SERIALIZED_BYTES.
        // The JSON wrapper overhead is ~77 bytes (object braces, key, type
        // discriminator, value quotes). 200 bytes of headroom keeps ofMap happy
        // while the metadata overhead (~150 bytes) pushes the Data total over
        // the line.
        val bigValue = "x".repeat(WorkInput.MAX_SERIALIZED_BYTES - 200)
        val nearCap =
            WorkInput.of(
                "blob" to WorkValue.StringValue(bigValue),
            )
        // Pre-condition: the WorkInput itself fits — if this assertion fails the
        // overhead constants have shifted and we need to re-tune the headroom.
        assertTrue(
            nearCap.toJson().toByteArray(Charsets.UTF_8).size <= WorkInput.MAX_SERIALIZED_BYTES,
            "test setup must build a WorkInput that passes the per-WorkInput cap",
        )
        val ex =
            assertFailsWith<IllegalArgumentException> {
                AndroidWorkInputMapper.toData(taskId, nearCap, ephemeral = false, maxAttempts = 10)
            }
        assertTrue(
            ex.message?.contains("${Data.MAX_DATA_BYTES}") == true,
            "error message should mention the Data cap: ${ex.message}",
        )
    }
}
