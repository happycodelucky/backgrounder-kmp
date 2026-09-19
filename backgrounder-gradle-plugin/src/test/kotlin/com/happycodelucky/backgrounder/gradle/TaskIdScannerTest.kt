package com.happycodelucky.backgrounder.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskIdScannerTest {
    private val testClassesDir: File =
        File(
            FixtureIds::class.java.protectionDomain.codeSource.location
                .toURI(),
        )

    @Test
    fun findsConstantsAtTopLevelInObjectsAndInCompanions() {
        val result = TaskIdScanner.scan(listOf(testClassesDir))
        assertEquals(
            listOf(
                "dev.example.fixtures.background-tick",
                "dev.example.fixtures.sync",
                "dev.example.fixtures.top-level",
                "dev.example.fixtures.upload",
            ),
            result.ids.map { it.id }.sorted(),
        )
    }

    @Test
    fun ignoresUnannotatedConstants() {
        val result = TaskIdScanner.scan(listOf(testClassesDir))
        assertEquals(emptyList(), result.ids.filter { it.id.endsWith(".ignored") })
    }

    @Test
    fun reportsNonConstantFields() {
        val result = TaskIdScanner.scan(listOf(testClassesDir))
        assertEquals(
            listOf("com.happycodelucky.backgrounder.gradle.FixtureWorker.NOT_CONST"),
            result.nonConstant.map { it.location },
        )
    }

    @Test
    fun attributesLocationToTheOwningClass() {
        val result = TaskIdScanner.scan(listOf(testClassesDir))
        val upload = result.ids.single { it.id.endsWith(".upload") }
        // A companion const val compiles to a static field on the outer class.
        assertEquals("com.happycodelucky.backgrounder.gradle.FixtureWorker.UPLOAD", upload.location)
    }

    @Test
    fun missingRootsAreIgnored() {
        assertEquals(ScanResult(emptyList(), emptyList()), TaskIdScanner.scan(listOf(File("/does/not/exist"))))
    }
}
