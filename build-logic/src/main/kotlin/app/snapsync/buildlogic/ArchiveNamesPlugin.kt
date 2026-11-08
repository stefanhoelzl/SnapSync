package app.snapsync.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension

/**
 * `snapsync.archive-names`, applied once, by the root: every Kotlin library's archives are named after its whole path
 * (`:core:composition:app` → `core-composition-app`), never after its last segment alone.
 *
 * Two modules may share a last segment — `:adapter:generic:app` and `:core:composition:app` — and a program installed
 * with `installDist` copies every runtime jar into one `lib/`, where two `app-jvm.jar`s collide and one is silently
 * dropped (measured: the journeys runner lost `KtorHttpTransport`, phase 5). A name built from the path cannot collide.
 */
class ArchiveNamesPlugin : Plugin<Project> {
    override fun apply(root: Project) {
        check(root == root.rootProject) { "snapsync.archive-names is the root's: it names every subproject" }
        root.subprojects {
            val project = this
            // Kotlin libraries only: an Android application's APK and AAB are named from the same property, and CI, the
            // screenshots and the journeys read them by name (`android-release.aab`).
            listOf("org.jetbrains.kotlin.multiplatform", "org.jetbrains.kotlin.jvm").forEach { kotlin ->
                plugins.withId(kotlin) {
                    project.extensions.configure(BasePluginExtension::class.java) {
                        archivesName.set(ArchiveNames.of(project.path))
                    }
                }
            }
        }
    }
}

/** The archive name a module's path gives it. */
object ArchiveNames {
    fun of(path: String): String = path.removePrefix(":").replace(':', '-')
}
