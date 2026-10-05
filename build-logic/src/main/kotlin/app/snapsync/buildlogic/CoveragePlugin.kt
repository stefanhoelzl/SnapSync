package app.snapsync.buildlogic

import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A module's coverage FLOORS (`docs/architecture.md`, "Coverage bounds"), the Kover arrangement every floored module
 * shares, declared once. Applying it applies Kover — so the instrumented set stays readable in each module's own
 * `plugins {}` block — and:
 *
 *  - filters the module's report to its OWN classes. The crediting edges (a producer's tests counting toward a consumer)
 *    are declared in the root build file; without this filter a credited module would measure the producer's classes
 *    too. For a module nothing credits, the filter is a no-op;
 *  - leaves out the code the string-resource generator writes;
 *  - verifies the floors on `check`.
 *
 * The NUMBERS stay in each module's build file, through [CoverageFloors] (`coverageFloors { … }`), beside the ratchet
 * record that justifies each one. Every number is a floor that may only rise; lowering one needs a stated forcing proof.
 */
class CoveragePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlinx.kover")
        val kover = project.extensions.getByType(KoverProjectExtension::class.java)
        kover.reports.filters.includes.projects.add(project.path)
        // Compose Resources' generated `Res` accessors (`docs/architecture.md`, "Localization"): one per string, written
        // by the generator rather than by anyone, and measuring them would floor a module on which strings a test reads.
        kover.reports.filters.excludes.packages("app.snapsync.ui.resources", "app.snapsync.ui.components.resources")
        kover.reports.total.verify.onCheck.set(true)
        project.extensions.create("coverageFloors", CoverageFloors::class.java, project.path, kover)
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
