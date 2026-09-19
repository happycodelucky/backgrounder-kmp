package com.happycodelucky.backgrounder.android

import com.happycodelucky.backgrounder.BackgroundTaskManager
import com.happycodelucky.backgrounder.SharedBackgroundTaskManager
import com.happycodelucky.backgrounder.shared
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Android has no zero-configuration path; an unconfigured `shared` must say what to do. */
class SharedBackgroundTaskManagerAndroidTest {
    @AfterTest
    fun tearDown() = SharedBackgroundTaskManager.resetForTests()

    @Test
    fun unconfiguredSharedExplainsBothFixes() {
        val error = assertFailsWith<IllegalStateException> { BackgroundTaskManager.shared }
        val message = error.message.orEmpty()
        assertTrue(message.contains("InitializationProvider"), message)
        assertTrue(message.contains("BackgroundTaskManager.configure(application)"), message)
    }
}
