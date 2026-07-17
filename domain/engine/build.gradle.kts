import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Full failure messages in CI: the Kotlin/Native simulator runner otherwise prints a terse
// "AssertionError at null:-1" with no expected/actual, which is useless for diagnosing
// platform-specific test failures.
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// MIGRATION STEP 4 left this module tests-only: the SQLDelight store + schema moved to
// `:adapter:generic`, the native driver factory to `:adapter:ios:ext-safe`. What remains is the
// `LedgerStoreContract` (+ its InMemory backend) and `SyncEngineTest`; the driver-backed tests
// extend the contract, so they stay here too (a test source set cannot be referenced across
// modules) and follow their subjects when the features move (steps 5/6).
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
        iosTest.dependencies {
            // NativeLedgerStoreTest runs the contract against the moved store over the native driver.
            implementation(project(":adapter:generic"))
            implementation(libs.sqldelight.driver.native)
        }
        jvmTest.dependencies {
            // SqlDelightLedgerStoreTest runs the contract against the moved store over the JVM driver.
            implementation(project(":adapter:generic"))
            implementation(libs.sqldelight.driver.sqlite)
        }
    }
}
