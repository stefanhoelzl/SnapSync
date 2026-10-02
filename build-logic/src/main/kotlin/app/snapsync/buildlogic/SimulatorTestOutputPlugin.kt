package app.snapsync.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

/**
 * The simulator test run's standard streams, shown beside the failure messages the root build turns FULL for every test
 * task. Applied by the modules whose `iosTest` output is worth reading on a CI log (`./gradlew iosPlatformTest`).
 */
class SimulatorTestOutputPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.withType(KotlinNativeSimulatorTest::class.java).configureEach {
            testLogging.showStandardStreams = true
        }
    }
}
