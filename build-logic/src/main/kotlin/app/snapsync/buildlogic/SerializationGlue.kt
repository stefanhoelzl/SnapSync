package app.snapsync.buildlogic

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * kotlinx-serialization's zero-mask missing-field check, one of the glue shapes [CoverageZero] excuses (its KDoc says
 * why no input reaches it), recognised in the BYTECODE.
 */
object SerializationGlue {

    private const val MARKER = "Lkotlinx/serialization/internal/SerializationConstructorMarker;)V"
    private const val PLUGIN_EXCEPTIONS = "kotlinx/serialization/internal/PluginExceptionsKt"

    /** The plugin's `(0 & seen) != 0`, one test per real instruction: `iconst_0; iload_1; iand; ifeq`. */
    private val OPENING: List<(AbstractInsnNode) -> Boolean> = listOf(
        { it is InsnNode && it.opcode == Opcodes.ICONST_0 },
        { it is VarInsnNode && it.opcode == Opcodes.ILOAD && it.`var` == 1 },
        { it.opcode == Opcodes.IAND },
        { it is JumpInsnNode && it.opcode == Opcodes.IFEQ },
    )

    /**
     * Every zero-mask missing-field check in [classFiles] (the module's compiled classes): a constructor ending in a
     * `SerializationConstructorMarker` whose code OPENS with `iconst_0; iload_1; iand; ifeq` — the plugin's
     * `(0 & seen) != 0` — and whose skipped block ends in `PluginExceptionsKt.throwMissingFieldException`. The site
     * excuses exactly that block's instructions and the one branch, on the line the block is attributed to.
     *
     * Anything else is not this shape and is not excused: a non-zero mask (`iconst_1`, `bipush`, …), a check on another
     * slot, a block that does not end in the plugin's throw.
     */
    fun find(classFiles: Iterable<ByteArray>): List<CoverageZero.Glue> = classFiles.flatMap { bytes ->
        val cls = readClass(bytes)
        val key = cls.reportKey() ?: return@flatMap emptyList()
        cls.methods.filter { it.name == "<init>" && it.desc.endsWith(MARKER) }.mapNotNull { method ->
            zeroMaskCheck(method.instructions.toArray())?.let { (line, instructions) ->
                CoverageZero.Glue(key, line, instructions, branches = 1, site = "${cls.name.replace('/', '.')}.<init>")
            }
        }
    }

    /** The opening `(0 & seen) != 0` check in [code]: its line and its skipped block's instruction count, or null. */
    private fun zeroMaskCheck(code: Array<AbstractInsnNode>): Pair<Int, Int>? {
        val jump = openingJump(code.filter { it.opcode >= 0 }) ?: return null
        val at = code.indexOf(jump)
        // The block the jump skips; empty — and so not this shape — when its label does not come after it.
        val block = code.slice(at + 1 until code.indexOf(jump.label)).filter { it.opcode >= 0 }
        val throws = block.lastOrNull()?.isMissingFieldThrow() == true
        return lineAt(code, at)?.takeIf { throws }?.let { it to block.size }
    }

    /** The `ifeq` of [real] (the code's real instructions) when they open with [OPENING], or null. */
    private fun openingJump(real: List<AbstractInsnNode>): JumpInsnNode? =
        real.zip(OPENING)
            .takeIf { pairs -> pairs.size == OPENING.size && pairs.all { (insn, matches) -> matches(insn) } }
            ?.last()?.first as? JumpInsnNode

    private fun AbstractInsnNode.isMissingFieldThrow() =
        this is MethodInsnNode && opcode == Opcodes.INVOKESTATIC && owner == PLUGIN_EXCEPTIONS &&
            name == "throwMissingFieldException"
}
