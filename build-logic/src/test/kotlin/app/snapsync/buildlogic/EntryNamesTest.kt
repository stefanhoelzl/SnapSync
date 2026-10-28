package app.snapsync.buildlogic

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntryNamesTest {

    private val base = "app.snapsync.scheduling.InboundEntryPoint"
    private val intent = "app.snapsync.ui.state.UiIntent"
    private val roots = setOf(base, intent)

    private val shapes = listOf(
        EntryNames.Shape(base, "java.lang.Object", emptyList()),
        EntryNames.Shape("app.snapsync.entrypoints.OnLink", base, emptyList()),
        EntryNames.Shape("app.snapsync.entrypoints.OnTap", base, emptyList()),
        EntryNames.Shape("app.snapsync.entrypoints.OnTerminate", base, emptyList()),
        EntryNames.Shape("app.snapsync.entrypoints.Deeper", "app.snapsync.entrypoints.OnLink", emptyList()),
        EntryNames.Shape("app.snapsync.entrypoints.Prelude", "java.lang.Object", emptyList()),
        EntryNames.Shape(intent, "java.lang.Object", emptyList()),
        EntryNames.Shape("app.snapsync.ui.state.UiIntent\$ConfirmCreate", "java.lang.Object", listOf(intent)),
    )

    @Test
    fun `the subjects are every class below a root, at any depth, by superclass or interface`() {
        assertEquals(
            setOf(
                "app.snapsync.entrypoints.Deeper",
                "app.snapsync.entrypoints.OnLink",
                "app.snapsync.entrypoints.OnTap",
                "app.snapsync.entrypoints.OnTerminate",
                "app.snapsync.ui.state.UiIntent\$ConfirmCreate",
            ),
            EntryNames.subjects(shapes, roots),
        )
    }

    @Test
    fun `a class is kept, renamed, merged or removed as the mapping shows it`() {
        val mapping = """
            app.snapsync.entrypoints.OnLink -> app.snapsync.entrypoints.OnLink:
                void run(app.snapsync.scheduling.Delivery,java.lang.Object) -> c
            app.snapsync.ui.state.UiIntent${'$'}ConfirmCreate -> y55:
            app.snapsync.entrypoints.Prelude -> b13:
                14:19:void app.snapsync.entrypoints.OnTap.run(app.snapsync.scheduling.Delivery,java.lang.Object):286:286 -> c
                java.lang.Object app.snapsync.entrypoints.Elsewhere${'$'}1.invoke(java.lang.Object):0 -> j
        """.trimIndent().lineSequence()

        val v = EntryNames.judge(
            EntryNames.subjects(shapes, roots),
            roots,
            shapes.map { it.name }.toSet(),
            mapping,
            emptyMap(),
        )

        assertEquals(listOf("app.snapsync.entrypoints.OnLink"), v.kept)
        assertEquals(listOf("app.snapsync.ui.state.UiIntent\$ConfirmCreate" to "y55"), v.renamed)
        assertEquals(listOf("app.snapsync.entrypoints.OnTap"), v.merged)
        assertEquals(listOf("app.snapsync.entrypoints.Deeper", "app.snapsync.entrypoints.OnTerminate"), v.removed)
        assertFalse(v.passes)
    }

    @Test
    fun `every subject on its own name passes, and a root no class declares fails`() {
        val mapping = sequenceOf("app.snapsync.entrypoints.OnLink -> app.snapsync.entrypoints.OnLink:")
        val subjects = setOf("app.snapsync.entrypoints.OnLink")

        assertTrue(EntryNames.judge(subjects, roots, roots, mapping, emptyMap()).passes)
        val missing = EntryNames.judge(subjects, roots, setOf(base), mapping, emptyMap())
        assertEquals(listOf(intent), missing.missingRoots)
        assertFalse(missing.passes)
    }

    @Test
    fun `a kept nested class passes only when the dex's InnerClass annotation names it`() {
        val nested = "app.snapsync.ui.state.UiIntent\$ConfirmCreate"
        val mapping = sequenceOf("$nested -> $nested:")

        val named = EntryNames.judge(setOf(nested), roots, roots, mapping, mapOf(nested to "ConfirmCreate"))
        val bare = EntryNames.judge(setOf(nested), roots, roots, mapping, emptyMap())

        assertTrue(named.passes)
        assertEquals(listOf(nested), bare.unnamed)
        assertFalse(bare.passes)
    }

    @Test
    fun `the InnerClass names are read from dexdump's listing, each to the class it annotates`() {
        val dump = """
            Class #0 annotations:
            Annotations on class
              VISIBILITY_SYSTEM Ldalvik/annotation/EnclosingClass; value=Lapp/snapsync/ui/state/UiIntent;
              VISIBILITY_SYSTEM Ldalvik/annotation/InnerClass; accessFlags=25 name="ConfirmCreate"

            Class #0            -
              Class descriptor  : 'Lapp/snapsync/ui/state/UiIntent${'$'}ConfirmCreate;'
              Access flags      : 0x0011 (PUBLIC FINAL)

            Class #1            -
              Class descriptor  : 'Lapp/snapsync/ui/state/UiIntent${'$'}CreateName;'
        """.trimIndent().lineSequence()

        assertEquals(mapOf("app.snapsync.ui.state.UiIntent\$ConfirmCreate" to "ConfirmCreate"), EntryNames.innerNames(dump))
    }

    @Test
    fun `a class's shape is read from its bytecode in dotted form`() {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC,
            "app/snapsync/ui/state/UiIntent\$ConfirmCreate",
            null,
            "java/lang/Object",
            arrayOf("app/snapsync/ui/state/UiIntent"),
        )
        writer.visitEnd()

        assertEquals(
            EntryNames.Shape("app.snapsync.ui.state.UiIntent\$ConfirmCreate", "java.lang.Object", listOf(intent)),
            EntryNames.shape(writer.toByteArray()),
        )
    }
}
