package app.snapsync.buildlogic

import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * A module's coverage measurement and its FLOORS (`docs/architecture.md`, "Coverage"), the Kover arrangement every
 * measured module shares, declared once. Applying it applies Kover — so the instrumented set stays readable in each
 * module's own `plugins {}` block — and:
 *
 *  - filters the module's report to its OWN classes. The crediting edges (a producer's tests counting toward a consumer)
 *    are declared in the root build file; without this filter a credited module would measure the producer's classes
 *    too. For a module nothing credits, the filter is a no-op;
 *  - leaves out the code the string-resource generator writes;
 *  - verifies the floors on `check`.
 *
 * The NUMBERS stay in each module's build file, through [CoverageFloors] (`coverageFloors { … }`), beside the ratchet
 * record that justifies each one. Every number is a floor that may only rise; lowering one needs a stated forcing proof.
 * A module that declares no floor is MEASURED only — the wiring modules, whose first numbers are being taken.
 *
 * The floors are the old model, kept by the modules that have not yet reached zero: a module at zero switches to
 * `snapsync.coverage-zero` ([CoverageZeroPlugin]) and deletes its floors in the same change.
 */
class CoveragePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val kover = measure(project, "snapsync.coverage-zero")
        kover.reports.total.verify.onCheck.set(true)
        project.extensions.create("coverageFloors", CoverageFloors::class.java, project.path, kover)
    }
}

/**
 * The ZERO gate (`docs/architecture.md`, "Coverage"): the module's Kover report may miss no instruction and no branch,
 * outside compiler-generated glue recognised by its shape ([CoverageZero] decides). Same measurement as
 * `snapsync.coverage`, which it replaces — applying both is refused, so switching a module is one visible edit: this
 * plugin id in, the `coverageFloors { }` block out.
 */
class CoverageZeroPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val kover = measure(project, "snapsync.coverage")
        val gate = project.tasks.register("coverageZero", CoverageZeroTask::class.java) {
            group = "verification"
            description = "Fails on any instruction or branch Kover reports missed, outside compiler-generated glue."
            dependsOn("koverXmlReport")
            modulePath.set(project.path)
            report.set(kover.reports.total.xml.xmlFile)
            sources.from(
                project.fileTree("src") {
                    include("*/kotlin/**/*.kt")
                    // Main sources only: a test source set's files are never in the report.
                    exclude("*Test/**", "test/**")
                },
            )
            // The JVM classes the report measures, read for glue the bytecode shows. Taken from the compile tasks
            // themselves, so the gate depends on them rather than reading a directory something else happens to fill.
            classes.from(project.tasks.matching { it.name == "compileKotlinJvm" || it.name == "compileKotlin" })
            verdict.set(project.layout.buildDirectory.file("reports/coverage-zero/verdict.txt"))
        }
        project.pluginManager.withPlugin("lifecycle-base") { project.tasks.named("check") { dependsOn(gate) } }
    }
}

/** The measurement both coverage plugins share; refuses a module that applies [other] too. */
private fun measure(project: Project, other: String): KoverProjectExtension {
    project.pluginManager.withPlugin(other) {
        throw GradleException(
            "${project.path} applies both snapsync.coverage and snapsync.coverage-zero: a module is floored OR at zero " +
                "(`docs/architecture.md`, \"Coverage\"). Switching means deleting the floors.",
        )
    }
    project.pluginManager.apply("org.jetbrains.kotlinx.kover")
    val kover = project.extensions.getByType(KoverProjectExtension::class.java)
    kover.reports.filters.includes.projects.add(project.path)
    // Compose Resources' generated `Res` accessors (`docs/architecture.md`, "Localization"): one per string, written
    // by the generator rather than by anyone, and measuring them would floor a module on which strings a test reads.
    kover.reports.filters.excludes.packages("app.snapsync.ui.resources", "app.snapsync.ui.components.resources")
    return kover
}

/** [CoverageZero]'s judgement of one module, on `check`. Prints what it excused, so the glue's growth stays visible. */
abstract class CoverageZeroTask : DefaultTask() {
    @get:Input
    abstract val modulePath: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val report: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    @get:OutputFile
    abstract val verdict: RegularFileProperty

    @TaskAction
    fun judge() {
        val classFiles = classes.files.flatMap { root -> root.walkTopDown().filter { it.extension == "class" }.toList() }
        val bytes = classFiles.map { it.readBytes() }
        val glue = CoverageZero.serializationGlue(bytes)
        val v = CoverageZero.judge(
            report.get().asFile.readText(),
            CoverageZero.index(sources.files),
            glue,
            composeGlue = ComposeGlue.scan(bytes),
        )
        val summary = "${modulePath.get()}: ${v.misses.size} missed line(s); excused ${v.excused.lines} @Composable " +
            "declaration line(s) — ${v.excused.instructions} instruction(s), ${v.excused.branches} branch(es) — " +
            "${v.composeGlue.size} body line(s) of Compose glue and ${v.excusedGlue.size} serialization zero-mask check(s)"
        logger.lifecycle(summary)
        // Every excused glue site by name, so what the gate lets through is read, not just counted.
        val sites = v.composeGlue.map {
            "  excused ${it.file}:${it.line}  ${it.instructions} instr / ${it.branches} branch  (${it.shapes})"
        } + v.excusedGlue.map {
            "  excused ${it.file}:${it.line}  ${it.instructions} instr / ${it.branches} branch  ${it.site} (zero-mask check)"
        }
        sites.forEach { logger.lifecycle(it) }
        val detail = v.mismatches.map { "  scan/bytecode: $it" } +
            v.misses.map { "  ${it.file}:${it.line}  missed ${it.instructions} instr / ${it.branches} branch  ${it.text}" }
        verdict.get().asFile.writeText((listOf(summary) + sites + detail).joinToString("\n", postfix = "\n"))
        if (!v.passes) {
            throw GradleException(
                "$summary — the zero gate allows none (`docs/architecture.md`, \"Coverage\"):\n" + detail.joinToString("\n"),
            )
        }
    }
}

/**
 * The two rule shapes the floored modules use, each named after the module as Kover reports it:
 * `"<path> aggregate"` and `"<path> package floor"`.
 */
abstract class CoverageFloors(private val path: String, private val kover: KoverProjectExtension) {

    /**
     * The whole module's floor: catches a broad slide that leaves every package above the [packageFloor]. Bounds are
     * whole percentages (Kover's `minValue` is an `Int`).
     */
    fun aggregate(instruction: Int, branch: Int) {
        kover.reports.total.verify.rule("$path aggregate") {
            bound {
                minValue.set(instruction)
                coverageUnits.set(CoverageUnit.INSTRUCTION)
            }
            bound {
                minValue.set(branch)
                coverageUnits.set(CoverageUnit.BRANCH)
            }
        }
    }

    /**
     * "No package here is worse than this": catches one package rotting behind well-tested neighbours, the shape an
     * untested class has. INSTRUCTION only — branch denominators per package run small enough that one arm moves the
     * number by double digits.
     */
    fun packageFloor(instruction: Int) {
        kover.reports.total.verify.rule("$path package floor") {
            groupBy.set(GroupingEntityType.PACKAGE)
            bound {
                minValue.set(instruction)
                coverageUnits.set(CoverageUnit.INSTRUCTION)
            }
        }
    }
}
