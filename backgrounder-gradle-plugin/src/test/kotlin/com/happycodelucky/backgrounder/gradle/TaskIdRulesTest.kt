package com.happycodelucky.backgrounder.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskIdRulesTest {
    @Test
    fun acceptsFreeFormIds() {
        listOf("dev.example.app.sync", "sync", "Sync Worker #1", "a/b:c").forEach { assertNull(TaskIdRules.problem(it), it) }
    }

    @Test
    fun rejectsBlankWhitespaceAndControlCharacters() {
        assertEquals("is blank", TaskIdRules.problem("  "))
        assertEquals("has leading or trailing whitespace", TaskIdRules.problem(" x"))
        assertEquals("contains control characters", TaskIdRules.problem("ab"))
    }

    @Test
    fun reverseDnsConvention() {
        assertTrue(TaskIdRules.looksReverseDns("dev.example.app.background-tick"))
        assertTrue(TaskIdRules.looksReverseDns("a.b"))
        assertFalse(TaskIdRules.looksReverseDns("sync"))
        assertFalse(TaskIdRules.looksReverseDns("dev.example app.sync"))
        assertFalse(TaskIdRules.looksReverseDns(".dev.example"))
    }
}
