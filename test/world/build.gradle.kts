plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}
// NOT INSTRUMENTED (`docs/architecture.md`, "Coverage is measured over unit tests only"):
// the tier `docs/testing.md` calls "The world hosts feature tests over the real stack" -
// real features driven against a composed world, which is integration by any reading.
//
// `disabledForAll` - note the spelling, not `disableForAll` - means this module is not instrumented,
// its coverage data is omitted from every report, and its test tasks are not triggered by report
// generation.
kover {
    currentProject {
        instrumentation {
            disabledForAll = true
        }
    }
}


// Shared test-infra: a controllable in-memory "world" the REAL app graph runs against — since
// migration step 10 composed through the SAME `snapSyncApp`/`uploadCore` the device shells call,
// over `:adapter:generic:fake`'s honest doubles; the world adds the backend store, the mini-edge, and the
// operator levers/wrappers that rig them (`docs/testing.md`). Consumed by BOTH
// `:app:desktop` (the full-stack harness) and `:test:integration`. Targets `jvm()` +
// `iosSimulatorArm64` ONLY — it never links into a shipped framework, so no `iosArm64`; its
// self-tests run on both per testing rule 1. It hosts NO port contracts: those live in
// `:test:contracts` (`docs/architecture.md`), so this module's main code carries no assertion
// library.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            api(project(":domain:feature"))
            api(project(":domain:compose"))
            // The shared host composition (`docs/architecture.md`, "One shared composition"): the world's core
            // and status host come from the same `snapSyncHost` the iOS shell calls, so the host its consumers
            // drive is the phone's.
            api(project(":domain:host"))
            // `World.statusHost` is presentation's container host; the host zone no longer exports it.
            api(project(":domain:presentation"))
            // The services: the backend services the core composes appear on the world's surface
            // (`World.manifestPublisher`, the extension tier's cycle); the gallery services (discovery, album
            // operations) are what the world's cycle reads the gallery through, as the phone's does; and the storage
            // services over the real JVM `Databases` serve the test that forcing the composition opens no database.
            api(project(":domain:services"))
            api(libs.orbit.core)
            // `api` (not `implementation`): the world's whole purpose is to hand the REAL stack's types
            // to its consumers (`:app:desktop`, `:test:integration`) — they appear across the world's
            // public API (composition helpers, honest fakes, wrappers), so they must leak transitively.
            api(project(":adapter:generic:fake"))
            // The production `HttpBackend` the mini-edge (or the real `api/`) serves.
            api(project(":adapter:generic:app"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.serialization.json)
        }
        // The REAL backend (`DenoBackend`, `docs/testing.md`): JVM-only, because it is a local
        // process `:test:edge` launches, which a Kotlin/Native target cannot.
        jvmMain.dependencies {
            implementation(project(":test:edge"))
            implementation(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            // The mini-edge is bound as the backend port contracts' `Fake` (`docs/architecture.md`):
            // the contracts and their shared setup live in `:test:contracts`' commonMain.
            implementation(project(":test:contracts"))
        }
    }
}

// The real backend's consumer contract (see `test/edge/build.gradle.kts`): `DenoWorldTest` starts it, so the
// deployment is resolved first and the backend's sources are inputs — a change touching only `api/` re-runs it.
val apiDir = rootProject.layout.projectDirectory.dir("api")
tasks.named<Test>("jvmTest") {
    dependsOn(":test:edge:resolveLocalDeployment")
    inputs.dir(apiDir.dir("src")).withPropertyName("liveEdgeSources")
    inputs.dir(apiDir.dir("migrations")).withPropertyName("liveEdgeMigrations")
    inputs.dir(rootProject.layout.projectDirectory.dir("deployments")).withPropertyName("liveEdgeDeployments")
    systemProperty("snapsync.apiDir", apiDir.asFile.absolutePath)
    systemProperty("snapsync.liveEdgeStore", layout.buildDirectory.dir("live-edge").get().asFile.absolutePath)
}
