import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Full failure messages in CI (the Kotlin/Native simulator runner otherwise prints a terse,
// useless "AssertionError at null:-1").
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// MIGRATION STEP 4: the SQLDelight store + schema moved to `:adapter:generic`, the iOS driver
// factory to `:adapter:ios:ext-safe` (killing step 0's interim engine edge with it). What remains
// is the in-memory store the world harness uses and the `DownloadStoreContract` the driver-backed
// jvmTests extend (a test source set cannot be referenced across modules; everything follows its
// subject when the features move, steps 5/6).
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(project(":adapter:generic"))
            implementation(libs.sqldelight.driver.sqlite)
        }
    }
}
