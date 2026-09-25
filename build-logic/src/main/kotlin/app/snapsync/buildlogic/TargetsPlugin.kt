package app.snapsync.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core"): every core zone and
 * every `:ui:*` module compiles for exactly `jvm`, `iosArm64` and `iosSimulatorArm64`, and declares no target list
 * of its own. A module MAY still configure a target this plugin declared (its test runtime, say).
 *
 * Adding a target — Android, one day — is an edit here and to that law's list, not a change to any other law.
 */
class TargetsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            val jdk = project.extensions.getByType(VersionCatalogsExtension::class.java)
                .named("libs").findVersion("jdk").get().requiredVersion.toInt()
            project.extensions.configure(KotlinMultiplatformExtension::class.java) {
                jvmToolchain(jdk)
                jvm()
                iosArm64()
                iosSimulatorArm64()
            }
        }
    }
}
