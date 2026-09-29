package app.snapsync.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * The `android` target, declared once (`docs/architecture.md`, "Zones inside the core"): every multiplatform module the
 * Android app links compiles for it — the core and `:ui:*` modules through [TargetsPlugin], which applies this, and the
 * modules that declare their own `jvm`/iOS list (`:adapter:generic:*`, the rig and what it links) by applying this
 * plugin beside it. A module declares no Android configuration of its own.
 *
 * Every value comes from `libs.versions.toml`: the SDK levels, and the bytecode the compilation emits — lowered from
 * the toolchain's `jdk` to `android-jvmTarget`, because D8 dexes it and the Android runtime runs it; the toolchain that
 * compiles it stays the one every other target uses.
 *
 * No host tests: `commonTest` already runs on the JVM and the iOS simulator, and an Android run of the same tests would
 * be a third copy on the JVM. What only an emulator can measure runs there (`docs/testing.md`, `android-emulator`).
 */
class AndroidTargetPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.pluginManager.apply("com.android.kotlin.multiplatform.library")
            val versions = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
            fun version(name: String): String = versions.findVersion(name).get().requiredVersion
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.extensions.configure(KotlinMultiplatformAndroidLibraryTarget::class.java) {
                namespace = namespaceOf(project.path)
                compileSdk = version("android-compileSdk").toInt()
                minSdk = version("android-minSdk").toInt()
                compilerOptions.jvmTarget.set(JvmTarget.fromTarget(version("android-jvmTarget")))
            }
        }
    }

    private companion object {
        /** `:adapter:generic:mock` → `app.snapsync.adapter.generic.mock`: unique per module, as an AAR's namespace must be. */
        fun namespaceOf(path: String): String =
            "app.snapsync" + path.split(':').filter { it.isNotEmpty() }.joinToString("") { "." + it.replace('-', '_') }
    }
}
