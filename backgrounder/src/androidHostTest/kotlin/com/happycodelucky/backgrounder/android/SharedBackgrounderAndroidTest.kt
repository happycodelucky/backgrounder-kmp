package com.happycodelucky.backgrounder.android

import com.happycodelucky.backgrounder.Backgrounder
import com.happycodelucky.backgrounder.SharedBackgrounder
import com.happycodelucky.backgrounder.shared
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Android has no zero-configuration path; an unconfigured `shared` must say what to do. */
class SharedBackgrounderAndroidTest {
    @AfterTest
    fun tearDown() = SharedBackgrounder.resetForTests()

    @Test
    fun unconfiguredSharedExplainsBothFixes() {
        val error = assertFailsWith<IllegalStateException> { Backgrounder.shared }
        val message = error.message.orEmpty()
        assertTrue(message.contains("InitializationProvider"), message)
        assertTrue(message.contains("Backgrounder.configure(application)"), message)
    }
}
