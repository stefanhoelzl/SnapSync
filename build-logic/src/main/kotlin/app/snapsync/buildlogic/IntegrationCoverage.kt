package app.snapsync.buildlogic

import kotlinx.kover.features.jvm.ClassFilters
import kotlinx.kover.features.jvm.KoverLegacyFeatures
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** The suite the integration measurement reads, and the binary report Kover's agent writes for its `test` task. */
internal const val INTEGRATION_SUITE = ":test:integration"
private const val SUITE_REPORT = "kover/bin-reports/test.ic"

/**
 * Registers [IntegrationCoverageTask] on the suite, judging every entry with integration targets against [goal]. Its
 * report is the suite's OWN binary report, so it measures what the suite alone covers and credits no module.
 */
internal fun registerIntegrationCoverage(root: Project, entries: List<ModuleCoverage>, goal: Pair<Int, Int>) {
    val suite = root.project(INTEGRATION_SUITE)
    val judged = entries.filter { e -> e.wholeSkip == null && e.integration?.let { it.targeted } == true }
    val task = suite.tasks.register("integrationCoverage", IntegrationCoverageTask::class.java) {
        group = "verification"
        description = "Fails when the integration suite alone covers a module below its target in the root build " +
            "file's `coverage { }`."
        dependsOn("test")
        report.set(suite.layout.buildDirectory.file(SUITE_REPORT))
        goalInstructions.set(goal.first)
        goalBranches.set(goal.second)
        judged.forEach { e ->
            val i = e.integration!!
            val module = root.project(e.path)
            modules.add(
                suite.objects.newInstance(IntegrationModule::class.java).apply {
                    path.set(e.path)
                    instructions.set(i.instructions ?: goal.first)
                    branches.set(i.branches ?: goal.second)
                    skipped.set(e.skippedPackages + i.skippedPackages)
                    classes.from(module.tasks.matching { it.name == "compileKotlinJvm" || it.name == "compileKotlin" })
                },
            )
        }
        reports.set(suite.layout.buildDirectory.dir("reports/integration-coverage"))
    }
    suite.pluginManager.withPlugin("lifecycle-base") { suite.tasks.named("check") { dependsOn(task) } }
}

/** One module the integration measurement judges: its JVM classes, its skipped packages and its targets. */
abstract class IntegrationModule {
    @get:Input
    abstract val path: Property<String>

    @get:Input
    abstract val instructions: Property<Int>

    @get:Input
    abstract val branches: Property<Int>

    @get:Input
    abstract val skipped: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection
}

/**
 * The INTEGRATION measurement (`docs/architecture.md`, "Coverage"): per module, what `:test:integration` alone
 * covers, through Kover's own report library over the suite's binary report and the module's JVM classes. Writes one
 * Kover XML per module and `summary.md`, the table the coverage phases read, and fails on every target missed and every
 * exception no longer needed — all of them in one run.
 */
abstract class IntegrationCoverageTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val report: RegularFileProperty

    @get:Input
    abstract val goalInstructions: Property<Int>

    @get:Input
    abstract val goalBranches: Property<Int>

    @get:Nested
    abstract val modules: ListProperty<IntegrationModule>

    @get:OutputDirectory
    abstract val reports: DirectoryProperty

    @TaskAction
    fun judge() {
        val dir = reports.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val measured = modules.get().map { m ->
            val xml = dir.resolve(m.path.get().trimStart(':').replace(':', '-') + ".xml")
            KoverLegacyFeatures.generateXmlReport(
                xml,
                listOf(report.get().asFile),
                m.classes.files.filter { it.isDirectory },
                emptyList(),
                m.path.get(),
                ClassFilters(emptySet(), m.skipped.get().map { "$it.*" }.toSet(), emptySet(), emptySet(), emptySet(), emptySet()),
            )
            Measured(m.path.get(), counters(xml.readText()), m.instructions.get(), m.branches.get())
        }
        val goal = goalInstructions.get() to goalBranches.get()
        dir.resolve("summary.md").writeText(IntegrationVerdict.summary(measured, goal))
        val problems = IntegrationVerdict.problems(measured, goal)
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Coverage of the integration suite alone (the root build file's `coverage { }`, `docs/architecture.md`, " +
                    "\"Coverage\"):\n" + problems.joinToString("\n") { "  $it" } +
                    "\nA drop is fixed by a test, never by lowering a number. Every module: ${dir.resolve("summary.md")}",
            )
        }
    }

    /** The report's totals, `INSTRUCTION`/`BRANCH` → covered of total. */
    private fun counters(xml: String): Map<String, Pair<Long, Long>> {
        val root = DocumentBuilderFactory.newInstance().apply {
            // The report names Kover's DTD; reading it would reach the network.
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }.newDocumentBuilder().parse(xml.byteInputStream()).documentElement
        val nodes = root.childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
            .filter { it.tagName == "counter" }
            .associate {
                val covered = it.getAttribute("covered").toLong()
                it.getAttribute("type") to (covered to covered + it.getAttribute("missed").toLong())
            }
    }
}
