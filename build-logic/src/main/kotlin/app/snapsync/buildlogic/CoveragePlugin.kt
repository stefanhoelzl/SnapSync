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
 * `snapsync.coverage`, applied once, by the root (`docs/architecture.md`, "Coverage"): the `coverage { }` block
 * ([CoverageExtension]) and everything it describes. Each entry's measurements are set up on its module once the root
 * build file has run — before any module's own build file, so no module declares its coverage itself:
 *
 *  - `unit(…)` applies Kover and the ZERO gate ([CoverageZeroTask], on `check`), filters the module's report to its
 *    OWN classes, and merges in the coverage of the modules that also cover it. Without the filter, a module covered by
 *    another would measure that module's classes too;
 *  - `integration(…)` is judged by `:test:integration`'s `integrationCoverage` ([IntegrationCoverageTask]): what the
 *    suite ALONE covers of the module, against its target or the block's `integrationGoal`;
 *  - a `skipped(…, packages)` leaves those packages out of the measurements it stands in.
 *
 * And it holds the block complete ([CoverageEntriesTask], on `check`): every module with a JVM target outside
 * `:test:*` and `:tools:*` has an entry, so no module is measured — or left out — by nobody's decision.
 */
class CoveragePlugin : Plugin<Project> {
    override fun apply(root: Project) {
        check(root == root.rootProject) { "snapsync.coverage is the root's: it describes every module" }
        root.pluginManager.apply("base")
        val coverage = root.extensions.create("coverage", CoverageExtension::class.java)
        root.afterEvaluate {
            val exists = { path: String -> root.findProject(path)?.buildFile?.exists() == true }
            val problems = coverage.entries.flatMap { it.problems(exists) } +
                coverage.entries.groupBy { it.path }.filterValues { it.size > 1 }.keys.map { "$it has more than one entry" }
            val goal = coverage.goal
            val unset = listOf("the block sets no integrationGoal(…)").takeIf { goal == null }.orEmpty()
            if (problems.isNotEmpty() || goal == null) {
                val lines = (problems + unset).joinToString("\n") { "  $it" }
                throw GradleException("The root build file's `coverage { }` is malformed:\n$lines")
            }
            registerIntegrationCoverage(root, coverage.entries, goal)
            coverage.entries.forEach { entry ->
                val unit = entry.unit?.takeIf { entry.wholeSkip == null && it.wholeSkip == null } ?: return@forEach
                zeroGate(root.project(entry.path), entry.skippedPackages + unit.skippedPackages)
                unit.alsoCoveredBy.forEach { root.project(entry.path).dependencies.add("kover", root.project(it)) }
            }
        }
        val complete = root.tasks.register("coverageEntries", CoverageEntriesTask::class.java) {
            group = "verification"
            description = "Fails when a JVM module has no entry in the root build file's `coverage { }`, or an entry " +
                "names a module Kover cannot measure."
            entries.set(root.provider { coverage.entries.map { it.path } })
            measurable.set(root.provider { root.subprojects.filter(::isMeasurable).map { it.path } })
        }
        root.tasks.named("check") { dependsOn(complete) }
    }

    /** A JVM target (Kover is JVM-only), outside the test and tool modules, which are measured by nothing. */
    private fun isMeasurable(p: Project): Boolean =
        !p.path.startsWith(":test:") && !p.path.startsWith(":tools:") &&
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
        val problems = missing.map { "  $it has no entry: describe its coverage, or skip it with a reason" } +
            unmeasurable.map { "  $it has an entry, but Kover cannot measure it (no JVM target, or a test/tool module)" }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The root build file's `coverage { }` (`docs/architecture.md`, \"Coverage\"):\n" + problems.joinToString("\n"),
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
