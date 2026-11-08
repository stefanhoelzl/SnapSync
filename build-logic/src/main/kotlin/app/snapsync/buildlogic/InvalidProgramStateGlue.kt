package app.snapsync.buildlogic

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * A throw of `InvalidProgramState`, the one thing a person writes that [CoverageZero] excuses (its KDoc says why no
 * input reaches it), recognised in the BYTECODE together with the one conditional arm that alone leads into it.
 */
object InvalidProgramStateGlue {

    private const val INVALID_PROGRAM_STATE = "app/snapsync/domain/program/InvalidProgramState"

    /**
     * The throw, one test per real instruction:
     * `new InvalidProgramState; dup; ldc "<because>"; invokespecial <init>(String); athrow`.
     */
    private val THROW: List<(AbstractInsnNode) -> Boolean> = listOf(
        { it is TypeInsnNode && it.opcode == Opcodes.NEW && it.desc == INVALID_PROGRAM_STATE },
        { it.opcode == Opcodes.DUP },
        { it is LdcInsnNode && it.cst is String },
        {
            it is MethodInsnNode && it.opcode == Opcodes.INVOKESPECIAL && it.owner == INVALID_PROGRAM_STATE &&
                it.name == "<init>" && it.desc == "(Ljava/lang/String;)V"
        },
        { it.opcode == Opcodes.ATHROW },
    )

    /** The opcodes after which control never falls through to the next instruction. */
    private val ENDS_FLOW = setOf(
        Opcodes.GOTO, Opcodes.ATHROW, Opcodes.RETURN, Opcodes.ARETURN, Opcodes.IRETURN, Opcodes.LRETURN,
        Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH,
    )

    /**
     * Every throw of `InvalidProgramState` in [classFiles] (the module's compiled classes): its block's instructions on
     * the line they are attributed to, and — where exactly one conditional arm (a jump's, or a switch case's) leads
     * into the block — that arm's one branch, on its jump's line. A block entered by plain fall-through or a `goto` is
     * not this shape and excuses nothing: something else runs on the way in. A block several arms lead into excuses its
     * instructions only: which arm is the impossible one is not the bytecode's to say.
     */
    fun find(classFiles: Iterable<ByteArray>): List<CoverageZero.Glue> = classFiles.flatMap { bytes ->
        val cls = readClass(bytes)
        val key = cls.reportKey() ?: return@flatMap emptyList()
        cls.methods.flatMap { method ->
            val site = "${cls.name.replace('/', '.')}.${method.name}"
            throwsIn(method.instructions.toArray()).flatMap { t ->
                listOfNotNull(
                    CoverageZero.Glue(key, t.line, t.instructions, 0, site, "invalid program state"),
                    t.armLine?.let { CoverageZero.Glue(key, it, 0, 1, site, "invalid program state's arm") },
                )
            }
        }
    }

    /** One recognised throw: its block's line and size, and the line of the one arm into it, when there is one. */
    private class Throw(val line: Int, val instructions: Int, val armLine: Int?)

    private fun throwsIn(code: Array<AbstractInsnNode>): List<Throw> {
        val real = code.indices.filter { code[it].opcode >= 0 }
        return real.indices.mapNotNull { at ->
            if (!isThrow(real.drop(at).take(THROW.size).map { code[it] })) return@mapNotNull null
            val popped = at > 0 && code[real[at - 1]].opcode == Opcodes.POP
            val start = if (popped) at - 1 else at
            val arms = armsInto(code, real, start) ?: return@mapNotNull null
            val line = lineAt(code, real[start]) ?: return@mapNotNull null
            Throw(line, THROW.size + if (popped) 1 else 0, arms.singleOrNull()?.let { lineAt(code, it) })
        }
    }

    private fun isThrow(shape: List<AbstractInsnNode>): Boolean =
        shape.size == THROW.size && shape.zip(THROW).all { (insn, matches) -> matches(insn) }

    /**
     * The indices of the conditional arms leading into the block that opens at real instruction [start] — a
     * conditional jump falling through into it or jumping to it, a switch case naming it — or null when it is also
     * entered by plain fall-through or a `goto`.
     */
    private fun armsInto(code: Array<AbstractInsnNode>, real: List<Int>, start: Int): List<Int>? {
        val before = real.getOrNull(start - 1)
        val labels = ((before?.plus(1) ?: 0) until real[start]).map { code[it] }.filterIsInstance<LabelNode>().toSet()
        val fallingIn = if (before == null) emptyList() else fallThroughArm(code, before)
        val jumpingIn = jumpArms(code, labels)
        return if (fallingIn == null || jumpingIn == null) null else fallingIn + jumpingIn
    }

    /**
     * What falls through from the real instruction at [before] into the block after it: its arm when it is a
     * conditional jump, nothing when it ends the flow, and null — plain fall-through — for anything else.
     */
    private fun fallThroughArm(code: Array<AbstractInsnNode>, before: Int): List<Int>? {
        val node = code[before]
        return when {
            node is JumpInsnNode && node.opcode != Opcodes.GOTO -> listOf(before)
            node.opcode in ENDS_FLOW -> emptyList()
            else -> null
        }
    }

    /**
     * The indices of the jumps and switches into [labels], one per target naming them, or null when a `goto` is
     * among them.
     */
    private fun jumpArms(code: Array<AbstractInsnNode>, labels: Set<LabelNode>): List<Int>? {
        val into = code.indices
            .associateWith { index -> code[index].jumpTargets().count { it in labels } }
            .filterValues { it > 0 }
        return into.takeUnless { arms -> arms.keys.any { code[it].opcode == Opcodes.GOTO } }
            ?.flatMap { (index, count) -> List(count) { index } }
    }
}
