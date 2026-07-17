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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
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
