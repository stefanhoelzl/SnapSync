import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldelight)
}

// Full failure messages in CI (the Kotlin/Native simulator runner otherwise prints a terse,
// useless "AssertionError at null:-1").
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
            // Interim edge for the App-Group id const (migration step 0, design D2): the six other
            // App-Group users already import engine's LEDGER_APP_GROUP; this dep dies at step 4
            // when all iosMain moves into the adapter modules together.
            implementation(project(":domain:engine"))
            implementation(libs.sqldelight.driver.native)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.driver.sqlite)
        }
    }
}

sqldelight {
    databases {
        create("DownloadDatabase") {
            packageName.set("app.snapsync.downloadstore.db")
            dialect(libs.sqldelight.dialect.sqlite)
        }
    }
}
