package app.snapsync.buildlogic.diagrams

import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiagramsTest {
    @Test
    fun `a port is an interface extending the marker, directly or through another port`() {
        val ports = listOf(
            "interface Port\ninterface EventPort<H> : Port\n",
            "interface Files : Port\ninterface Gallery : GalleryReader, EventPort<GalleryHandlers>\n",
            "interface GalleryReader : Port\nsealed interface WriteOutcome\ninterface Handle\n",
        )
        assertEquals(
            mapOf("Files" to emptySet(), "Gallery" to setOf("GalleryReader"), "GalleryReader" to emptySet()),
            PortsDiagram.ports(ports),
        )
    }

    @Test
    fun `each port lists its implementations by module, the mocks in their own column`() {
        val ios = "import app.snapsync.ports.Gallery\nclass IosGallery : Gallery\n"
        val android = "import app.snapsync.ports.*\nclass AndroidFiles(val c: Ctx) : Files {\n}\n"
        val mock = "import app.snapsync.ports.Files\nimport app.snapsync.ports.Clock\n" +
            "object FilesMock {\n    class Face : Files, Clock\n}\n"
        val modules = listOf(
            PortsDiagram.Module(":adapter:ios", listOf(ios)),
            PortsDiagram.Module(":adapter:android", listOf(android)),
            PortsDiagram.Module(":adapter:generic:mock", listOf(mock)),
            // A module's own type sharing a port's simple name is not the port.
            PortsDiagram.Module(":core:data", listOf("sealed interface Files\nclass Gathered : Files\n")),
        )
        val ports = mapOf(
            "Clock" to emptySet(),
            "Files" to emptySet(),
            "Gallery" to setOf("GalleryReader"),
            "GalleryReader" to emptySet(),
        )
        val table = PortsDiagram.render(ports, modules, mock = ":adapter:generic:mock")
        assertContains(table, "| `Clock` | — | FilesMock.Face |\n")
        assertContains(table, "| `Files` | `:adapter:android` AndroidFiles | FilesMock.Face |\n")
        assertContains(table, "| `GalleryReader` | `:adapter:ios` IosGallery | — |\n")
    }

    @Test
    fun `a ports scan that finds no port fails instead of drawing an empty table`() {
        assertFailsWith<GradleException> { PortsDiagram.render(emptyMap(), emptyList(), mock = ":m") }
    }

    @Test
    fun `the module graph groups modules by top-level directory and sorts by code point`() {
        val graph = ModulesDiagram.render(
            modules = listOf(":ui:state", ":core:domain", ":core:data"),
            edges = listOf(":core:data" to ":core:domain", ":ui:state" to ":core:domain"),
        )
        assertContains(
            graph,
            "```mermaid\nflowchart LR\n" +
                "  subgraph core\n    core_data[\":core:data\"]\n    core_domain[\":core:domain\"]\n  end\n" +
                "  subgraph ui\n    ui_state[\":ui:state\"]\n  end\n" +
                "  core_data --> core_domain\n  ui_state --> core_domain\n```\n",
        )
    }
}
