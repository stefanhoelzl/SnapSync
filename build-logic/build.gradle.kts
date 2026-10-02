plugins {
    `kotlin-dsl`
}

// The plugin's classes load into the Gradle daemon, so they target the daemon's JVM (21), not the app's `jdk`.
kotlin {
    jvmToolchain(21)
}

dependencies {
    // compileOnly, deliberately: the plugin runs against the ONE Kotlin Gradle Plugin the root build already loaded
    // (`apply false` there). A second KGP on this build's runtime classpath would load on its own classloader, and
    // the Apple targets' global build services then fail to cast across the two — the failure the root's comment
    // records.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    // compileOnly for the same reason: the Android target is configured through the ONE AGP the root build loaded.
    compileOnly(libs.android.gradle)
    // compileOnly for the same reason: `snapsync.coverage` configures the ONE Kover plugin the root build loaded.
    compileOnly("org.jetbrains.kotlinx:kover-gradle-plugin:${libs.versions.kover.get()}")
}

gradlePlugin {
    plugins {
        register("targets") {
            id = "snapsync.targets"
            implementationClass = "app.snapsync.buildlogic.TargetsPlugin"
        }
        register("android") {
            id = "snapsync.android"
            implementationClass = "app.snapsync.buildlogic.AndroidTargetPlugin"
        }
        register("coverage") {
            id = "snapsync.coverage"
            implementationClass = "app.snapsync.buildlogic.CoveragePlugin"
        }
        register("simulatorTestOutput") {
            id = "snapsync.simulator-test-output"
            implementationClass = "app.snapsync.buildlogic.SimulatorTestOutputPlugin"
        }
    }
}
