package app.snapsync.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * A module of the core's LAYERS (`docs/architecture.md`, "The layers"): `:domain:inbound`, `:domain:scheduling`,
 * `:domain:entrypoints` — the modules the restructuring creates, which are born clean rather than ratcheted down.
 * Everything such a module shares is declared once, here:
 *
 *  - the allowed targets ([TargetsPlugin]) on Kotlin Multiplatform;
 *  - coverage at ZERO from creation ([CoverageZeroPlugin]) — there is no floor to switch from;
 *  - the simulator test run's standard streams ([SimulatorTestOutputPlugin]);
 *  - the shared test dependencies (`kotlin-test`, `kotlinx-coroutines-test`).
 *
 * The module's own build file then declares only its edges — which is the part a reviewer reads.
 */
class LayerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply(TargetsPlugin::class.java)
        project.pluginManager.apply(CoverageZeroPlugin::class.java)
        project.pluginManager.apply(SimulatorTestOutputPlugin::class.java)
        val libs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        project.dependencies.add("commonTestImplementation", "org.jetbrains.kotlin:kotlin-test")
        project.dependencies.add("commonTestImplementation", libs.findLibrary("coroutines-test").get())
    }
}
