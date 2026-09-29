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
 * `commonTest` runs on the EMULATOR too — a device test (`androidDeviceTest`, from the `test` source-set tree), for the
 * reason it runs on the iOS simulator: the code ships on ART, after D8, over the platform's own SQLite and Compose
 * renderer, none of which the JVM run exercises. Not a host test: that is the JVM again, over a stubbed `android.jar`.
 * The device tests need an emulator, so `./gradlew build` does not run them; the `android-emulator` CI job does
 * (`connectedAndroidDeviceTest`, `docs/testing.md`). A module with no common tests but device tests of its own
 * (`src/androidDeviceTest`: an Android adapter's contract bindings, which need the platform) declares it the same way.
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
                if (hasDeviceTests(project)) {
                    withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
                        instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    }
                }
            }
            // Compose Multiplatform 1.11 registers a resource copy for the device test and leaves its output unset, which
            // fails validation; no test here carries Compose resources.
            project.tasks.matching { it.name == "copyAndroidDeviceTestComposeResourcesToAndroidAssets" }
                .configureEach { enabled = false }
            if (hasDeviceTests(project)) {
                kotlin.sourceSets.named("androidDeviceTest") {
                    dependencies {
                        implementation(versions.findLibrary("androidx-test-runner").get())
                        // `kotlin.test` for every module's common tests on ART — some take it from `jvmTest` alone.
                        implementation("org.jetbrains.kotlin:kotlin-test")
                    }
                }
            }
        }
    }

    private companion object {

        fun hasDeviceTests(project: Project): Boolean =
            project.file("src/commonTest").isDirectory || project.file("src/androidDeviceTest").isDirectory

        /** `:adapter:generic:mock` → `app.snapsync.adapter.generic.mock`: unique per module, as an AAR's namespace must be. */
        fun namespaceOf(path: String): String =
            "app.snapsync" + path.split(':').filter { it.isNotEmpty() }.joinToString("") { "." + it.replace('-', '_') }
    }
}
