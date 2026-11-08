package app.snapsync.buildlogic.diagrams

import app.snapsync.buildlogic.QualityPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import java.io.File

/**
 * `snapsync.diagrams`, applied once, by the root through `snapsync.conventions` (`docs/architecture.md`, "Diagrams"):
 * the two diagrams under `architecture/`, written by `build` and committed — readable on GitHub, and current because
 * CI fails while the build leaves the tree dirty.
 *
 *  - `architecture/modules.md` — the module graph ([ModulesDiagram]), from the project model;
 *  - `architecture/ports.md` — every port and what implements it ([PortsDiagram]), from the sources.
 *
 * Both models are read at configuration time, once every project is evaluated, so neither task touches a `Project`
 * when it runs.
 */
class DiagramsPlugin : Plugin<Project> {
    override fun apply(root: Project) {
        check(root == root.rootProject) { "snapsync.diagrams is the root's: it reads every module" }
        val dir = root.layout.projectDirectory.dir("architecture")
        val modules = root.tasks.register("architectureModulesDiagram", ModulesDiagramTask::class.java) {
            description = "Writes architecture/modules.md from the Gradle project model."
            diagram.set(dir.file("modules.md"))
        }
        val ports = root.tasks.register("architecturePortsDiagram", PortsDiagramTask::class.java) {
            description = "Writes architecture/ports.md from :core:ports and every production module's sources."
            diagram.set(dir.file("ports.md"))
            mock.set(MOCK)
        }
        root.gradle.projectsEvaluated {
            val (paths, edges) = ModulesDiagram.model(root)
            modules.configure {
                this.modules.set(paths)
                this.edges.set(edges.map { (from, to) -> "$from $to" })
            }
            ports.configure {
                this.ports.from(root.fileTree(PORTS) { include("**/*.kt") })
                this.modules.set(production(root).map { module -> sourcesOf(root, module) })
            }
        }
        val diagrams = root.tasks.register("architectureDiagrams") {
            group = "documentation"
            description = "Writes every diagram under architecture/ (`docs/architecture.md`, \"Diagrams\")."
            dependsOn(modules, ports)
        }
        root.tasks.named("build") { dependsOn(diagrams) }
    }

    /** The modules production code lives in: the core, the adapters, the screens and the roots. */
    private fun production(root: Project): List<Project> = root.subprojects
        .filter { it.buildFile.exists() && it.path.split(':')[1] in PRODUCTION }
        .sortedBy { it.path }

    private fun sourcesOf(root: Project, module: Project): PortsDiagramModule =
        root.objects.newInstance(PortsDiagramModule::class.java).apply {
            path.set(module.path)
            sources.from(
                module.fileTree("src") {
                    include("**/*.kt")
                    exclude(QualityPlugin.TEST_SOURCE_SET_DIRS.map { "$it/**" })
                },
            )
        }

    private companion object {
        const val PORTS = "core/ports/src/commonMain"
        const val MOCK = ":adapter:generic:mock"
        val PRODUCTION = setOf("adapter", "app", "core", "ui")
    }
}

/**
 * A generated file that is committed: written only when its text changed, and said so, so a local build that fixed one
 * tells.
 */
internal object Committed {
    fun write(file: File, text: String, logger: Logger) {
        if (file.isFile && file.readText(Charsets.UTF_8) == text) return
        file.parentFile.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        logger.lifecycle("rewrote ${file.name}: commit it")
    }
}
