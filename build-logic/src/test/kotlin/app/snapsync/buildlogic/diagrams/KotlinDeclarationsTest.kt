package app.snapsync.buildlogic.diagrams

import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinDeclarationsTest {
    @Test
    fun `a declaration's supertypes are read past its constructor, generics and delegates`() {
        val text = """
            |internal class IosFiles(
            |    private val root: String,
            |    val clock: Map<String, Int>,
            |) : Files, EventPort<FileHandlers>, Closeable by Noop() {
            |    object Nested : Port
            |}
            |interface Gallery : GalleryReader,
            |    app.snapsync.ports.GalleryImport
            |
            |fun interface Clock : Port
        """.trimMargin()
        assertEquals(
            listOf(
                Declaration("class", "IosFiles", listOf("Files", "EventPort", "Closeable"), topLevel = true),
                Declaration("object", "Nested", listOf("Port"), topLevel = false),
                Declaration("interface", "Gallery", listOf("GalleryReader", "GalleryImport"), topLevel = true),
                Declaration("interface", "Clock", listOf("Port"), topLevel = true),
            ),
            KotlinDeclarations.of(text),
        )
    }

    @Test
    fun `commented-out code and class literals declare nothing, a string keeps its slashes`() {
        val text = """
            |// class Old : Port
            |/* class Older : Port /* nested */ still a comment */
            |val url = "https://example.com/*not a comment"
            |val k = Files::class
            |class Live : Port
        """.trimMargin()
        assertEquals(listOf("Live"), KotlinDeclarations.of(text).map { it.name })
    }

    @Test
    fun `blanking keeps every offset and newline`() {
        val text = "a // b\n/* c\n d */ e \"// f\" '\\''"
        val blanked = CommentBlanker(text).blanked()
        assertEquals(text.length, blanked.length)
        assertEquals("a     \n    \n      e \"// f\" '\\''", blanked)
    }
}
