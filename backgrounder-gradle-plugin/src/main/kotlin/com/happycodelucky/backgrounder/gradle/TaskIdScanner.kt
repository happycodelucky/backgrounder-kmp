package com.happycodelucky.backgrounder.gradle

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes
import java.io.File

/** One `@BGTaskSchedulerPermittedIdentifier` field found in a class file. */
internal data class ScannedTaskId(
    val id: String,
    val owner: String,
    val fieldName: String,
) {
    val location: String
        get() = "$owner.$fieldName"
}

/** An annotated field that has no compile-time constant value. */
internal data class NonConstantTaskId(
    val owner: String,
    val fieldName: String,
) {
    val location: String
        get() = "$owner.$fieldName"
}

internal data class ScanResult(
    val ids: List<ScannedTaskId>,
    val nonConstant: List<NonConstantTaskId>,
)

/**
 * Reads class files with ASM and returns every field carrying
 * `@BGTaskSchedulerPermittedIdentifier`.
 *
 * Kotlin compiles `const val` into a static field with a `ConstantValue`
 * attribute; ASM hands that to `visitField` as `value`. A `@BGTaskSchedulerPermittedIdentifier`
 * on a field with a null `value` is a non-`const` property — reported
 * separately so the task can fail with a precise message.
 *
 * `BINARY` retention makes the annotation a `RuntimeInvisibleAnnotation`, so
 * `visitAnnotation` sees it with `visible = false`. We accept either.
 */
internal object TaskIdScanner {
    const val ANNOTATION_DESCRIPTOR: String = "Lcom/happycodelucky/backgrounder/BGTaskSchedulerPermittedIdentifier;"

    fun scan(roots: Iterable<File>): ScanResult {
        val ids = mutableListOf<ScannedTaskId>()
        val nonConstant = mutableListOf<NonConstantTaskId>()
        roots
            .asSequence()
            .filter { it.exists() }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "class" } }
            .sorted()
            .forEach { scanClass(it, ids, nonConstant) }
        return ScanResult(ids = ids, nonConstant = nonConstant)
    }

    private fun scanClass(
        file: File,
        ids: MutableList<ScannedTaskId>,
        nonConstant: MutableList<NonConstantTaskId>,
    ) {
        val reader = ClassReader(file.readBytes())
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                private var owner = ""

                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    owner = name.replace('/', '.')
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor =
                    object : FieldVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(
                            annotationDescriptor: String,
                            visible: Boolean,
                        ): AnnotationVisitor? {
                            if (annotationDescriptor == ANNOTATION_DESCRIPTOR) {
                                if (value is String) {
                                    ids += ScannedTaskId(id = value, owner = owner, fieldName = name)
                                } else {
                                    nonConstant += NonConstantTaskId(owner = owner, fieldName = name)
                                }
                            }
                            return null
                        }
                    }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }
}
