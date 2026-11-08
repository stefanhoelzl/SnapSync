package app.snapsync.buildlogic

import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * `snapsync.coverage`, applied once, by the root through `snapsync.conventions` (`docs/architecture.md`, "Coverage"):
 * every module's `coverage { }` ([ModuleCoverage]), declared in the module's OWN build file, and everything it
 * describes. Once a module's build file has run, its measurements are set up on it:
 *
 *  - `unit(…)` applies Kover and the ZERO gate ([CoverageZeroTask], on `check`), filters the module's report to its
 *    OWN classes, and merges in the coverage of the modules that also cover it. Without the filter, a module covered by
 *    another would measure that module's classes too;
 *  - `integration(…)` is judged by `:test:integration`'s `integrationCoverage` ([IntegrationCoverageTask]): what the
 *    suite ALONE covers of the module, against its target or the suite's `integrationCoverage { goal(…) }`;
 *  - a `skipped(…, packages)` leaves those packages out of the measurements it stands in.
 *
 * And it holds the description complete ([CoverageEntriesTask], on `check`): every module with a JVM target outside
 * `:test:*` declares its coverage, so no module is measured — or left out — by nobody's decision.
 */
class CoveragePlugin : Plugin<Project> {
    override fun apply(root: Project) {
        check(root == root.rootProject) { "snapsync.coverage is the root's: it describes every module" }
        root.pluginManager.apply("base")
        root.subprojects {
            val module = this
            val coverage = extensions.create("coverage", ModuleCoverage::class.java, path)
            if (path == INTEGRATION_SUITE) extensions.create("integrationCoverage", IntegrationGoal::class.java)
            afterEvaluate { measure(module, coverage) }
        }
        root.gradle.projectsEvaluated {
            val suite = root.project(INTEGRATION_SUITE)
            val goal = suite.extensions.getByType(IntegrationGoal::class.java).goal
                ?: throw GradleException(
                    "${suite.buildFile.relativeTo(root.rootDir)} sets no `integrationCoverage { goal(…) }` " +
                        "(`docs/architecture.md`, \"Coverage\")",
                )
            registerIntegrationCoverage(root, root.subprojects.map(::coverageOf).filter { it.declared }, goal)
        }
        val complete = root.tasks.register("coverageEntries", CoverageEntriesTask::class.java) {
            group = "verification"
            description = "Fails when a JVM module's build file declares no `coverage { }`, or a module Kover cannot " +
                "measure declares one."
            entries.set(root.provider { root.subprojects.filter { coverageOf(it).declared }.map { it.path } })
            measurable.set(root.provider { root.subprojects.filter(::isMeasurable).map { it.path } })
        }
        root.tasks.named("check") { dependsOn(complete) }
    }

    /** Sets up [module]'s measurements from its build file's `coverage { }`, failing on a malformed one. */
    private fun measure(module: Project, coverage: ModuleCoverage) {
        if (!coverage.declared) return
        val root = module.rootProject
        val problems = coverage.problems { root.findProject(it)?.buildFile?.exists() == true }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "${module.buildFile.relativeTo(root.rootDir)}'s `coverage { }` is malformed:\n" +
                    problems.joinToString("\n") { "  $it" },
            )
        }
        val unit = coverage.unit?.takeIf { coverage.wholeSkip == null && it.wholeSkip == null } ?: return
        zeroGate(module, coverage.skippedPackages + unit.skippedPackages)
        unit.alsoCoveredBy.forEach { module.dependencies.add("kover", module.project(it)) }
    }

    private fun coverageOf(p: Project): ModuleCoverage = p.extensions.getByType(ModuleCoverage::class.java)

    /** A JVM target (Kover is JVM-only), outside the test modules, which are measured by nothing. */
    private fun isMeasurable(p: Project): Boolean =
        !p.path.startsWith(":test:") &&
            (p.plugins.hasPlugin("org.jetbrains.kotlin.jvm") || "compileKotlinJvm" in p.tasks.names)
}

/**
 * The ZERO gate on [project] (`docs/architecture.md`, "Coverage"): its Kover report may miss no instruction and no
 * branch, outside compiler-generated glue recognised by its shape ([CoverageZero] decides). [skipped] packages leave
 * the report.
 */
private fun zeroGate(project: Project, skipped: List<String>) {
    project.pluginManager.apply("org.jetbrains.kotlinx.kover")
    val kover = project.extensions.getByType(KoverProjectExtension::class.java)
    kover.reports.filters.includes.projects.add(project.path)
    if (skipped.isNotEmpty()) kover.reports.filters.excludes.packages(skipped)
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

/** The `coverage { }` block's completeness, on `check`: [entries] and [measurable] must name the same modules. */
abstract class CoverageEntriesTask : DefaultTask() {
    @get:Input
    abstract val entries: ListProperty<String>

    @get:Input
    abstract val measurable: ListProperty<String>

    @TaskAction
    fun judge() {
        val missing = measurable.get() - entries.get().toSet()
        val unmeasurable = entries.get() - measurable.get().toSet()
        val undeclared = missing.map {
            "  $it declares no `coverage { }` in its build file: describe its coverage, or skip it with a reason"
        }
        val unmeasured = unmeasurable.map {
            "  $it declares `coverage { }`, but Kover cannot measure it (no JVM target, or a test module)"
        }
        val problems = undeclared + unmeasured
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Every module's `coverage { }` (`docs/architecture.md`, \"Coverage\"):\n" + problems.joinToString("\n"),
            )
        }
    }
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
        val glue = CoverageZero.serializationGlue(bytes) + CoverageZero.invalidProgramStateGlue(bytes)
        val v = CoverageZero.judge(
            report.get().asFile.readText(),
            CoverageZero.index(sources.files),
            glue,
            composeGlue = ComposeGlue.scan(bytes),
        )
        val summary = "${modulePath.get()}: ${v.misses.size} missed line(s); excused ${v.excused.lines} @Composable " +
            "declaration line(s) — ${v.excused.instructions} instruction(s), ${v.excused.branches} branch(es) — " +
            "${v.composeGlue.size} body line(s) of Compose glue and ${v.excusedGlue.size} other glue or " +
            "InvalidProgramState site(s)"
        logger.lifecycle(summary)
        // Every excused glue site by name, so what the gate lets through is read, not just counted.
        val sites = v.composeGlue.map {
            "  excused ${it.file}:${it.line}  ${it.instructions} instr / ${it.branches} branch  (${it.shapes})"
        } + v.excusedGlue.map {
            "  excused ${it.file}:${it.line}  ${it.instructions} instr / ${it.branches} branch  ${it.site} (${it.shape})"
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
