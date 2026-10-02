package app.snapsync.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/** The Gradle property that turns a build into a RIG build. */
const val RIG_PROPERTY = "snapsync.rig"

/**
 * The rig build's switch, read in one place (`docs/architecture.md`, "A build-time-only module is contained by
 * compilation"): `-Psnapsync.rig=true` makes this a RIG build, and anything else — the property absent included — a
 * production one.
 *
 * A build script reaches it as `import app.snapsync.buildlogic.rigEnabled` and spells every rig-only line
 * `if (rigEnabled)`, the spelling `MockContainmentTest` looks for. A plain function, not a Gradle extension: the
 * Kotlin DSL's generated accessor for a `Boolean` extension is `inline`, and inlining it inside AGP's `android {}`
 * block crashed the script compiler's code generation for `:app:android` when this was tried.
 */
val Project.rigEnabled: Boolean
    get() = providers.gradleProperty(RIG_PROPERTY).map(String::toBoolean).getOrElse(false)

/**
 * Puts [rigEnabled] on a build script's classpath. A script sees `build-logic`'s classes only once it applies one of
 * its plugins, and the shipped roots apply no other.
 */
class RigPlugin : Plugin<Project> {
    override fun apply(project: Project) = Unit
}
