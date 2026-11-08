package app.snapsync.buildlogic

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode

/*
 * The bytecode reading the zero coverage gate's glue recognisers share ([ComposeGlue], [SerializationGlue],
 * [InvalidProgramStateGlue]): a class file as a tree, the report key of its source, and the jumps and lines of its
 * code.
 */

/** [bytes] (one compiled class) read as a tree, without the stack map frames no glue reader looks at. */
internal fun readClass(bytes: ByteArray): ClassNode =
    ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_FRAMES) }

/** The class's source file keyed as Kover's report names it, `<package path>/<file name>`; null when it names none. */
internal fun ClassNode.reportKey(): String? = sourceFile?.let { file ->
    val pkg = name.substringBeforeLast('/', "")
    if (pkg.isEmpty()) file else "$pkg/$file"
}

/** The labels this instruction can jump to: a jump's target, a switch's cases and default; none for anything else. */
internal fun AbstractInsnNode.jumpTargets(): List<LabelNode> = when (this) {
    is JumpInsnNode -> listOf(label)
    is TableSwitchInsnNode -> labels + dflt
    is LookupSwitchInsnNode -> labels + dflt
    else -> emptyList()
}

/** The source line of [code]'s instruction at [index]: the last line number at or before it, or null. */
internal fun lineAt(code: Array<AbstractInsnNode>, index: Int): Int? =
    code.take(index + 1).filterIsInstance<LineNumberNode>().lastOrNull()?.line
