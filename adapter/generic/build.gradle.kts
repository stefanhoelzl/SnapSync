// `:adapter:generic` (spec `module-architecture`): platform-free technology implementations of the
// `:domain` ports — the Ktor HTTP clients and the SQLDelight stores. Named for the technology,
// placed by linkage: generic code links everywhere (JVM harness, app, extension), so this module
// carries no platform source set. Packages keep their pre-migration names deliberately (decision
// D2 of `extract-adapter-modules`): every gate and diagram scopes by directory, and the pure-move
// diff is the review artifact; package normalization rides the feature-move steps.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            api(libs.coroutines.core)
            // HttpClient appears in every Ktor adapter's public constructor — consumers construct
            // their own engine (Darwin on device, MockEngine in the world/harness), so the type is API.
            api(libs.ktor.client.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.kotlinx.serialization.json)
            // TimeZone appears in SystemTimeZone's override of the `TimeZoneSource` port (step 9).
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        // The SQLDelight stores' contract-backed tests (re-homed from the deleted `:domain:engine` /
        // `:domain:download-store` modules at migration step 10). The shared contracts live in
        // `:test:world`'s commonMain — the one test-infra surface every implementor can reach — so
        // these are per-target source sets, NOT the intermediate `iosTest`: `:test:world` has no
        // `iosArm64`, and the device-arm test compilation must not ask for it (tests run on the
        // simulator only, per testing rule 1).
        val jvmTest by getting {
            dependencies {
                implementation(project(":test:world"))
                implementation(libs.sqldelight.driver.sqlite)
            }
        }
        val iosSimulatorArm64Test by getting {
            dependencies {
                implementation(project(":test:world"))
                implementation(libs.sqldelight.driver.native)
            }
        }
    }
}

// Full failure messages in CI: the Kotlin/Native simulator runner otherwise prints a terse
// "AssertionError at null:-1" with no expected/actual (config carried over with the re-homed
// NativeLedgerStoreTest from `:domain:engine`).
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// Both databases live here (decision D3 of `extract-adapter-modules`): one module per withheld
// technology, so the two SQLDelight schemas share it, each from its own source dir (two `create`
// blocks may not share srcDirs). Generated packages are unchanged from their pre-migration homes —
// they are not runtime identity (the pinned db *filenames* are).
sqldelight {
    databases {
        create("LedgerDatabase") {
            packageName.set("app.snapsync.engine.db")
            srcDirs.setFrom("src/commonMain/sqldelight/ledger")
            // SQLite 3.18: the default dialect rejects ALTER TABLE … DROP COLUMN (2.sqm needs it).
            dialect(libs.sqldelight.dialect.sqlite)
        }
        create("DownloadDatabase") {
            packageName.set("app.snapsync.downloadstore.db")
            srcDirs.setFrom("src/commonMain/sqldelight/download")
            dialect(libs.sqldelight.dialect.sqlite)
        }
    }
}
