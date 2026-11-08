package app.snapsync.buildlogic.diagrams

import app.snapsync.buildlogic.Committed
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * `architecture/ports.md`: every port and what implements it.
 *
 * The ports are READ from `:core:ports`, never guessed: an interface declared there that extends the `Port` marker,
 * directly or through another port (`EventPort` is the marker of the ports that deliver events). An implementation is
 * a production class or object, in any module, naming the port as a supertype; the mocks (`:adapter:generic:mock`)
 * have their own column. Which contract clauses prove each port is the port grid's (`docs/testing.md`), not this
 * table's.
 */
object PortsDiagram {
    /** The marker every port extends, and the marker of the ports that deliver events: kinds, not ports. */
    private val MARKERS = setOf("Port", "EventPort")

    /** One module's production source: its path and the text of each Kotlin file. */
    data class Module(val path: String, val texts: List<String>)

    /** The package the ports are declared in: an implementation's file imports a port from it by name. */
    private const val PACKAGE = "app.snapsync.ports"

    /**
     * The ports [portTexts] declare — interfaces extending a marker, directly or through another port — each with every
     * port it extends, transitively: implementing a port implements those too.
     */
    fun ports(portTexts: List<String>): Map<String, Set<String>> {
        val interfaces = portTexts.flatMap(KotlinDeclarations::of)
            .filter { it.kind == "interface" && it.topLevel }
            .associate { it.name to it.supertypes.toSet() }
        val ports = MARKERS.toMutableSet()
        do {
            val grown = interfaces.filter { (name, supers) -> name !in ports && supers.any { it in ports } }.keys
            ports += grown
        } while (grown.isNotEmpty())
        val named = ports - MARKERS
        return named.associateWith { superPorts(it, interfaces, named) }.toSortedMap()
    }

    private fun superPorts(port: String, interfaces: Map<String, Set<String>>, ports: Set<String>): Set<String> {
        val found = mutableSetOf<String>()
        var frontier = setOf(port)
        while (frontier.isNotEmpty()) {
            frontier = frontier.flatMap { interfaces[it].orEmpty() }.filter { it in ports && found.add(it) }.toSet()
        }
        return found
    }

    fun render(ports: Map<String, Set<String>>, modules: List<Module>, mock: String): String {
        if (ports.isEmpty()) throw GradleException("the ports scan found no port in :core:ports — did the module move?")
        val implementations = modules
            .flatMap { module -> module.texts.flatMap { implementationsIn(it, module.path, ports) } }
            .groupBy { it.port }
        return buildString {
            append(HEADER)
            append("| Port | Implementations (by module) | Mock |\n|---|---|---|\n")
            ports.keys.forEach { port ->
                val (mocks, adapters) = implementations[port].orEmpty().partition { it.module == mock }
                append("| `$port` | ${cell(adapters, withModule = true)} | ${cell(mocks, withModule = false)} |\n")
            }
        }
    }

    /**
     * The classes and objects in [text] that implement a port, and the ports each implements. A supertype is the port
     * only where the file imports it by that name: a module's own type that shares a port's simple name is not it. A
     * nested declaration is named through the top-level one it sits in (`BuildInfoMock.Face`).
     */
    private fun implementationsIn(text: String, module: String, ports: Map<String, Set<String>>): List<Implementation> {
        val visible = ports.keys.filter { imports(text, it) }.toSet()
        var outer = ""
        return KotlinDeclarations.of(text).flatMap { declaration ->
            if (declaration.topLevel) outer = declaration.name
            val name = if (declaration.topLevel) declaration.name else "$outer.${declaration.name}"
            val direct = declaration.supertypes.filter { it in visible }.takeIf { declaration.kind != "interface" }
            val all = direct.orEmpty().flatMap { ports.getValue(it) + it }.distinct()
            all.map { Implementation(it, module, name) }
        }
    }

    private fun imports(text: String, port: String): Boolean =
        Regex("""^import ${Regex.escape(PACKAGE)}\.(\*|$port)$""", RegexOption.MULTILINE).containsMatchIn(text)

    private data class Implementation(val port: String, val module: String, val name: String)

    private fun cell(implementations: List<Implementation>, withModule: Boolean): String =
        implementations.sortedWith(compareBy({ it.module }, { it.name }))
            .joinToString("<br>") { if (withModule) "`${it.module}` ${it.name}" else it.name }
            .ifEmpty { "—" }

    private val HEADER = """
        |# Port × adapter matrix
        |
        |Generated by `./gradlew build` (`architecturePortsDiagram`): every interface of `:core:ports` that extends
        |`Port` (directly or through another port), and every production class or object, by module, that implements
        |it — naming it, imported from `:core:ports`, as a supertype, or implementing a port that extends it. The
        |mocks (`:adapter:generic:mock`) have their own column. Which contract clauses prove a port is the port
        |grid's (`docs/testing.md`). Do not edit: the build rewrites this file, and CI fails while the committed copy
        |differs.
        |
        |
    """.trimMargin()
}

/** One module's production Kotlin sources, as the ports diagram reads them. */
abstract class PortsDiagramModule {
    @get:Input
    abstract val path: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
}

/** Writes [PortsDiagram] from `:core:ports`' declarations and every production module's sources. */
abstract class PortsDiagramTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ports: ConfigurableFileCollection

    @get:Nested
    abstract val modules: ListProperty<PortsDiagramModule>

    @get:Input
    abstract val mock: Property<String>

    @get:OutputFile
    abstract val diagram: RegularFileProperty

    /** Where a path in a message is relative to. */
    @get:Internal
    abstract val rootDir: DirectoryProperty

    @TaskAction
    fun render() {
        val modules = modules.get().map { m ->
            PortsDiagram.Module(m.path.get(), m.sources.files.sorted().map { it.readText() })
        }
        val ports = PortsDiagram.ports(ports.files.sorted().map { it.readText() })
        val text = PortsDiagram.render(ports, modules, mock.get())
        Committed.write(diagram.get().asFile, text, logger, rootDir.get().asFile)
    }
}
