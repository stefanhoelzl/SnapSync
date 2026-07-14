import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Full failure messages in CI (see :domain:engine): the Kotlin/Native simulator runner otherwise
// prints a terse "AssertionError at null:-1". The real-Keychain round-trip tests below run ONLY on
// the simulator, so a useless failure message there is a useless test.
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
