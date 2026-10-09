package app.snapsync.buildlogic

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * The Compose compiler's glue on BODY lines (`docs/architecture.md`, "Coverage"), read from the bytecode by the shape
 * the compiler gives it — the two shapes [CoverageZero] excuses besides the declaration lines. Each is reported per
 * source line as an ALLOWANCE: the most instructions and branches that shape can account for there. The gate excuses
 * a line only when its misses fit inside the allowance, so anything beyond — a user branch, an unrun lambda — fails.
 *
 *  - **A remembered lambda's arms** ([LineGlue.remembered]). A lambda literal in a composable body is cached by the
 *    compiler, and the cache's validity test — the `$changed` mask checks and `Composer.changed`/`changedInstance`
 *    calls feeding one boolean — takes its other arms only on a recomposition. Recognised as a conditional jump in a
 *    composable method whose condition reads a `$changed` parameter or calls one of those [Composer] methods; the
 *    allowance is the instructions from the first such condition to the boolean's store, and two arms per jump.
 *    Because two arms per jump is an upper bound on Kover's count (it reports 4 for 3 such jumps), the allowance is
 *    WITHHELD from a line that also holds a user branch in a composable method ([LineGlue.userBranch]) or code of
 *    another method the report does not list as run in full ([LineGlue.otherMethods], checked by [CoverageZero]) — a
 *    lambda body that never ran, or one written on the same line, which Kover counts without listing.
 *  - **The dead arm of an exhaustive `when`** ([LineGlue.deadArm]). Kotlin ends a `when` over a sealed or enum
 *    subject with a `NoWhenBranchMatchedException` throw no value reaches; in a composable the compiler wraps it in a
 *    replace group, so the block holds `startReplaceGroup` → `endReplaceGroup` → the throw. Kover filters the throw
 *    itself but not the group calls, nor a conditional jump into the block (a sealed `when`'s last `is` arm). The
 *    allowance is exact: the block's instructions other than the throw sequence, and one arm per conditional jump into
 *    it (a switch's default into it Kover already filters).
 *
 * One jump is neither shape nor the user's: the compiler's trace guard, `if (isTraceInProgress()) traceEventEnd()`,
 * which it attributes to a composable's last body line (and `traceEventStart` to its declaration). Kover filters it, so
 * it earns no allowance; it is only kept from counting as a [LineGlue.userBranch]. Recognised exactly: an `ifeq` on
 * `isTraceInProgress`'s result, skipping only the trace call.
 *
 * Nothing else is recognised: a shape outside these two is a miss for a test or a restructure, never for this file.
 */
object ComposeGlue {

    data class Allowance(val instructions: Int, val branches: Int) {
        operator fun plus(o: Allowance) = Allowance(instructions + o.instructions, branches + o.branches)

        companion object {
            val NONE = Allowance(0, 0)
        }
    }

    /** A method other than a composable one with code on a line: the report's own counters must show it fully run. */
    data class MethodRef(val owner: String, val name: String, val desc: String)

    data class LineGlue(
        val remembered: Allowance = Allowance.NONE,
        val deadArm: Allowance = Allowance.NONE,
        val userBranch: Boolean = false,
        val otherMethods: Set<MethodRef> = emptySet(),
    )

    private const val COMPOSER = "androidx/compose/runtime/Composer"
    private const val COMPOSER_KT = "androidx/compose/runtime/ComposerKt"
    private const val NO_WHEN = "kotlin/NoWhenBranchMatchedException"
    private val VALIDITY_CALLS = setOf("changed", "changedInstance")

    /** Per `<package path>/<source file>` (the report's key), per line: the glue the [classes] hold there. */
    fun scan(classes: Iterable<ByteArray>): Map<String, Map<Int, LineGlue>> {
        val out = mutableMapOf<String, MutableMap<Int, LineGlue>>()
        for (bytes in classes) {
            val cls = ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_FRAMES) }
            val file = cls.sourceFile ?: continue
            val key = cls.name.substringBeforeLast('/', "").let { if (it.isEmpty()) file else "$it/$file" }
            val lines = out.getOrPut(key) { mutableMapOf() }
            for (m in cls.methods) {
                val merge: (Int, (LineGlue) -> LineGlue) -> Unit = { nr, f -> lines[nr] = f(lines[nr] ?: LineGlue()) }
                if (m.isComposable()) scanComposable(m, merge) else noteOther(cls.name, m, merge)
            }
        }
        return out
    }

    private fun MethodNode.isComposable() = Type.getArgumentTypes(desc).any { it.internalName == COMPOSER }

    private fun noteOther(owner: String, m: MethodNode, merge: (Int, (LineGlue) -> LineGlue) -> Unit) {
        val ref = MethodRef(owner, m.name, m.desc)
        lineOf(m).values.toSet().forEach { nr -> merge(nr) { it.copy(otherMethods = it.otherMethods + ref) } }
    }

    private fun scanComposable(m: MethodNode, merge: (Int, (LineGlue) -> LineGlue) -> Unit) {
        val line = lineOf(m)
        val insns = m.instructions.toArray().filter { it.opcode >= 0 }
        val changedSlots = dirtySlots(insns, changedSlots(m))
        val dead = deadBlocks(m)
        val deadInsns = dead.flatMap { it.second }.toSet()
        // The remembered-lambda jumps, grouped by line, each with the index its condition starts at.
        val validity = mutableMapOf<Int, MutableList<Pair<Int, Int>>>()
        insns.forEachIndexed { i, insn ->
            val nr = line[insn] ?: return@forEachIndexed
            if (insn !is JumpInsnNode && insn !is TableSwitchInsnNode && insn !is LookupSwitchInsnNode) return@forEachIndexed
            if (insn.opcode == Opcodes.GOTO) return@forEachIndexed
            val start = conditionStart(insns, i, line)
            val condition = insns.subList(start, i)
            when {
                insn is JumpInsnNode && condition.any { it.readsSlot(changedSlots) || it.callsComposer(VALIDITY_CALLS) } ->
                    validity.getOrPut(nr) { mutableListOf() } += start to i
                // A sealed `when`'s last `is` test: its false arm is dead, its true arm the user's.
                insn is JumpInsnNode && dead.any { it.first == insn.label } ->
                    merge(nr) { it.copy(deadArm = it.deadArm + Allowance(0, 1), userBranch = true) }
                insn in deadInsns -> Unit
                insn is JumpInsnNode && insn.isTraceGuard() -> Unit
                else -> merge(nr) { it.copy(userBranch = true) }
            }
        }
        for ((nr, jumps) in validity) {
            val first = jumps.minOf { it.first }
            val last = jumps.maxOf { it.second }
            // The cache's test ends in the boolean it stores; a mask check that stores nothing (a skip check) is not
            // this shape, and counts as any other branch.
            val end = (last until insns.size).firstOrNull { insns[it].opcode == Opcodes.ISTORE && line[insns[it]] == nr }
            if (end == null) {
                merge(nr) { it.copy(userBranch = true) }
            } else {
                val counted = (first..end).count { line[insns[it]] == nr }
                merge(nr) { it.copy(remembered = it.remembered + Allowance(counted, 2 * jumps.size)) }
            }
        }
        for ((_, block) in dead) {
            block.filterNot { it.isThrowSequence() }.groupBy { line[it] }.forEach { (nr, group) ->
                if (nr != null) merge(nr) { it.copy(deadArm = it.deadArm + Allowance(group.size, 0)) }
            }
        }
    }

    /** Where a jump's condition starts: just after the previous branch or store on its line, or the line's start. */
    private fun conditionStart(insns: List<AbstractInsnNode>, jump: Int, line: Map<AbstractInsnNode, Int>): Int {
        var k = jump
        while (k > 0) {
            val prev = insns[k - 1]
            if (line[prev] != line[insns[jump]]) break
            if (prev is JumpInsnNode || prev is TableSwitchInsnNode || prev is LookupSwitchInsnNode) break
            if (prev is VarInsnNode && prev.opcode in Opcodes.ISTORE..Opcodes.ASTORE) break
            k--
        }
        return k
    }

    /**
     * The `NoWhenBranchMatchedException` blocks that carry Compose group calls: each block's entry label (a jump
     * target) and its instructions through the throw.
     */
    private fun deadBlocks(m: MethodNode): List<Pair<LabelNode, List<AbstractInsnNode>>> {
        val targets = m.instructions.toArray().flatMap { insn ->
            when (insn) {
                is JumpInsnNode -> listOf(insn.label)
                is TableSwitchInsnNode -> insn.labels + insn.dflt
                is LookupSwitchInsnNode -> insn.labels + insn.dflt
                else -> emptyList()
            }
        }.toSet()
        return m.instructions.toArray().filter { it is TypeInsnNode && it.opcode == Opcodes.NEW && it.desc == NO_WHEN }
            .mapNotNull { new ->
                var entry: AbstractInsnNode = new
                while (entry.previous != null && !(entry is LabelNode && entry in targets)) entry = entry.previous
                val label = (entry as? LabelNode)?.takeIf { it in targets } ?: return@mapNotNull null
                val block = generateSequence(label.next) { it.next }
                    .takeWhile { it.previous?.opcode != Opcodes.ATHROW }
                    .filter { it.opcode >= 0 }
                    .toList()
                label.takeIf { block.any { it.callsComposer(null) } }?.let { it to block }
            }
    }

    /**
     * The compiler's trace guard, `if (isTraceInProgress()) traceEventStart(…)` / `traceEventEnd()`: an `ifeq` on the
     * boolean `isTraceInProgress` just pushed, skipping a block whose one call is the trace event.
     */
    private fun JumpInsnNode.isTraceGuard(): Boolean {
        val condition = previous { it.opcode >= 0 }
        if (opcode != Opcodes.IFEQ || !(condition is MethodInsnNode && condition.isTrace("isTraceInProgress"))) return false
        val guarded = generateSequence(next) { it.next }.takeWhile { it != label }.filterIsInstance<MethodInsnNode>().toList()
        return guarded.singleOrNull()?.run { isTrace("traceEventStart") || isTrace("traceEventEnd") } == true
    }

    private fun MethodInsnNode.isTrace(name: String) = opcode == Opcodes.INVOKESTATIC && owner == COMPOSER_KT && this.name == name

    private fun AbstractInsnNode.previous(p: (AbstractInsnNode) -> Boolean) = generateSequence(previous) { it.previous }.firstOrNull(p)

    private fun AbstractInsnNode.isThrowSequence() = when (this) {
        is TypeInsnNode -> desc == NO_WHEN
        is MethodInsnNode -> owner == NO_WHEN
        else -> opcode == Opcodes.DUP || opcode == Opcodes.ATHROW
    }

    private fun AbstractInsnNode.callsComposer(names: Set<String>?) =
        this is MethodInsnNode && owner == COMPOSER && (names == null || name in names)

    private fun AbstractInsnNode.readsSlot(slots: Set<Int>) = this is VarInsnNode && opcode == Opcodes.ILOAD && `var` in slots

    /** The local slots of the `$changed`/`$default` bitmask parameters: every `int` after the [COMPOSER] parameter. */
    private fun changedSlots(m: MethodNode): Set<Int> {
        var slot = if (m.access and Opcodes.ACC_STATIC != 0) 0 else 1
        var afterComposer = false
        val out = mutableSetOf<Int>()
        for (t in Type.getArgumentTypes(m.desc)) {
            if (afterComposer && t.sort == Type.INT) out += slot
            if (t.internalName == COMPOSER) afterComposer = true
            slot += t.size
        }
        return out
    }

    /**
     * [params] and every local the compiler copies them into: it reads the `$changed` bits through a `$dirty` local it
     * assigns from them (and widens), so a store whose value reads a tracked slot tracks its own.
     */
    private fun dirtySlots(insns: List<AbstractInsnNode>, params: Set<Int>): Set<Int> {
        val out = params.toMutableSet()
        do {
            val before = out.size
            insns.forEachIndexed { i, insn ->
                if (insn !is VarInsnNode || insn.opcode != Opcodes.ISTORE) return@forEachIndexed
                val value = insns.subList(0, i).takeLastWhile { it !is VarInsnNode || it.opcode !in Opcodes.ISTORE..Opcodes.ASTORE }
                    .takeLastWhile { it !is JumpInsnNode && it !is LabelNode }
                if (value.any { it.readsSlot(out) }) out += insn.`var`
            }
        } while (out.size != before)
        return out
    }

    /** Each real instruction's source line, as the line number table assigns it. */
    private fun lineOf(m: MethodNode): Map<AbstractInsnNode, Int> {
        val out = mutableMapOf<AbstractInsnNode, Int>()
        var current: Int? = null
        for (insn in m.instructions) {
            if (insn is LineNumberNode) current = insn.line
            if (insn.opcode >= 0) current?.let { out[insn] = it }
        }
        return out
    }
}
