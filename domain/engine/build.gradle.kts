import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldelight)
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

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.driver.sqlite)
        }
    }
}

sqldelight {
    databases {
        create("LedgerDatabase") {
            packageName.set("app.snapsync.engine.db")
            // The default non-Android dialect is SQLite 3.18, whose grammar rejects
            // `ALTER TABLE … DROP COLUMN` (a 3.35 feature) used by the version-drop migration
            // (2.sqm). Both drivers run SQLite ≫ 3.35 at runtime; this only raises the compile-time
            // parser floor.
            dialect(libs.sqldelight.dialect.sqlite)
        }
    }
}
