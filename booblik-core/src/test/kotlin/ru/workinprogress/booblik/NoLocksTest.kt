package ru.workinprogress.booblik

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M-62: "no locks in `booblik-core`" stops being a claim and becomes a build failure.
 *
 * The design rests on it. Ordering in the log comes from a single coroutine owning the writer, and
 * readers never take anything because the file is append-only — that is the whole reason there is
 * no contention to tune. A `synchronized` block added in good faith three months from now would not
 * break a test; it would quietly make the thing slower and the reasoning wrong. So the property is
 * checked where it actually lives: in the bytecode.
 *
 * ## What counts as a violation
 *
 * `MONITORENTER` (a `synchronized` block) and `ACC_SYNCHRONIZED` (a synchronized method), plus any
 * reference to `kotlinx.coroutines.sync.Mutex`. Atomics and `@Volatile` are not locks and are used
 * freely; `LongAdder` is not either.
 *
 * ## What it cannot see
 *
 * Locks inside the JDK. `CopyOnWriteArrayList` takes one internally, which is precisely why
 * `PartitionLog` does not use it — but this test would not have caught that, and the comment there
 * is doing the work instead.
 */
class NoLocksTest {
    @Test
    fun `booblik-core compiles to bytecode with no monitors and no Mutex`() {
        val classes = Path.of("build/classes/kotlin/main")
        assertTrue(Files.exists(classes), "expected compiled classes at $classes — run from the module directory")

        val violations = mutableListOf<String>()
        Files.walk(classes).use { paths ->
            paths.filter { it.extension == "class" }.forEach { file ->
                ClassReader(file.readBytes()).accept(Scanner(file, violations), ClassReader.SKIP_FRAMES)
            }
        }

        assertTrue(
            violations.isEmpty(),
            "booblik-core must not lock. Ordering comes from single ownership, not from mutual " +
                "exclusion, and every one of these takes that apart:\n" + violations.joinToString("\n"),
        )
    }

    private class Scanner(
        private val file: Path,
        private val violations: MutableList<String>,
    ) : ClassVisitor(Opcodes.ASM9) {
        private var className = file.fileName.toString()

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            className = name
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            if (access and Opcodes.ACC_SYNCHRONIZED != 0) {
                violations += "  $className.$name is a synchronized method"
            }
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitInsn(opcode: Int) {
                    if (opcode == Opcodes.MONITORENTER) {
                        violations += "  $className.$name contains a synchronized block"
                    }
                }

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    methodName: String,
                    methodDescriptor: String,
                    isInterface: Boolean,
                ) {
                    if (owner.startsWith(MUTEX)) {
                        violations += "  $className.$name calls $owner.$methodName"
                    }
                }
            }
        }
    }

    private companion object {
        const val MUTEX = "kotlinx/coroutines/sync/Mutex"
    }
}
