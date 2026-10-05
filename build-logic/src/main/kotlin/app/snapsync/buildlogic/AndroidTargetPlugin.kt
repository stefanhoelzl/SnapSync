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
 * A module's DEVICE tests (`src/androidDeviceTest`) are what only the platform can answer — an Android adapter's contract
 * bindings, over ART, the platform's SQLite, Keystore, MediaStore and WorkManager. `commonTest` is NOT among them: the
 * shared logic runs once, on the JVM, under `./gradlew build` (`docs/testing.md`, "Where each test runs"). They need an
 * emulator, so `build` does not run them; `./gradlew androidPlatformTest` does, on the managed device declared here
 * ([MANAGED_DEVICE], booted and torn down by the Android Gradle plugin), and `connectedAndroidDeviceTest` on an
 * emulator you booted yourself. Either way the upload contract's transfer fixture is served on the host for the run
 * ([TransferFixtureService]) and handed to the tests as the `fixture` instrumentation argument.
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
                // A module with strings (`docs/architecture.md`, "Localization") must package them: the KMP
                // Android library ships no resources unless asked, and the app then renders every string blank.
                if (project.file("src/commonMain/composeResources").isDirectory) androidResources.enable = true
                if (hasDeviceTests(project)) {
                    // The `test` tree names the source set `androidDeviceTest`. It would also pull in a `commonTest`,
                    // which is why a module declares device tests only where it holds no common tests (the adapters).
                    withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
                        instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                        instrumentationRunnerArguments["fixture"] = TransferFixtureService.FIXTURE_ADDRESS
                        managedDevices {
                            localDevices.create(MANAGED_DEVICE) {
                                // The phone the screen tests are measured on, and the one the `snapsync-android` skill
                                // creates. The GPU mode is the root `gradle.properties`' (SwiftShader's JIT segfaults).
                                device = "Pixel 6"
                                apiLevel = version("android-targetSdk").toInt()
                                systemImageSource = "google"
                                require64Bit = true
                                testedAbi = "x86_64"
                            }
                        }
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
                        implementation("org.jetbrains.kotlin:kotlin-test")
                    }
                }
                serveTransferFixture(project)
                // What `./gradlew androidPlatformTest` runs in each module with device tests; `android-build` in CI.
                project.tasks.register("androidPlatformTest") {
                    group = "verification"
                    description = "Runs this module's device tests on the managed emulator ($MANAGED_DEVICE)."
                    dependsOn("${MANAGED_DEVICE}AndroidDeviceTest")
                }
            }
        }
    }

    /** The device-test runs — on the managed device, or a connected one — start the host's transfer fixture first. */
    private fun serveTransferFixture(project: Project) {
        val fixture = project.gradle.sharedServices.registerIfAbsent(
            TransferFixtureService.NAME,
            TransferFixtureService::class.java,
        ) {
            parameters.script.set(project.rootProject.layout.projectDirectory.file("scripts/transfer-fixture.py"))
            parameters.log.set(project.rootProject.layout.buildDirectory.file("transfer-fixture.log"))
            parameters.port.set(TransferFixtureService.PORT)
        }
        project.tasks.matching { it.name in DEVICE_TEST_TASKS }.configureEach {
            usesService(fixture)
            doFirst { fixture.get().ensureStarted() }
        }
    }

    private companion object {

        /** The managed device `androidPlatformTest` runs the device tests on: `<name>AndroidDeviceTest`. */
        const val MANAGED_DEVICE = "pixel6"

        private val DEVICE_TEST_TASKS = setOf("connectedAndroidDeviceTest", "${MANAGED_DEVICE}AndroidDeviceTest")

        /** A device test is a platform-bound test: only a module with `src/androidDeviceTest` declares one. */
        fun hasDeviceTests(project: Project): Boolean = project.file("src/androidDeviceTest").isDirectory

        /** `:adapter:generic:mock` → `app.snapsync.adapter.generic.mock`: unique per module, as an AAR's namespace must be. */
        fun namespaceOf(path: String): String =
            "app.snapsync" + path.split(':').filter { it.isNotEmpty() }.joinToString("") { "." + it.replace('-', '_') }
    }
}
