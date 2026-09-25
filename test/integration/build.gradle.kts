plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}
// NOT INSTRUMENTED (`docs/architecture.md`, "Coverage is measured over unit tests only"):
// the seam-to-UI-state integration surface. It drives the real core over the whole graph, so counting it
// would let a thick integration suite stand in for a thin unit suite.
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

// The seam-to-UI-state integration surface (`docs/testing.md`, "The seam-to-UI-state
// integration surface"). Every test starts the control channel's JVM host in-process — a world composed by the
// same shared host composition the iOS shell calls, over the mini-edge — and drives it through the protocol's
// typed client (`:test:control`) ONLY. So a test names no world, port, flow or composition type, and one test
// body could target any host: the client's compile path carries only the wire types and the read-model types
// (the rig declares its own dependencies `implementation`), which is what holds that rule.
//
// JVM-only, and that FORGOES the composed graph's Kotlin/Native run over fakes (`docs/testing.md`,
// "Every test runs on every target its module declares"): the host is an in-process HTTP server, a JVM target.
// The native composition is still exercised by the core's own `iosSimulatorArm64` unit tests, and by the
// simulator app running the composed graph over real adapters under the contracts and the journeys.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    testImplementation(project(":test:control"))
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
}

// ---- The all-real journeys ----
// (`docs/testing.md`, "All-real journeys are the contracts' safety net")
//
// A few end-to-end runs with EVERY system real: the rig build of the iOS app on ONE simulator, the real backend
// served locally, the real photo library. The second member is played by the journey itself, over the backend's
// public HTTP surface with real JPEG bytes (`Member`), so to the app it is a foreign member like any device.
// Written against the same typed client as the tests above, and run ONLY by the `ios-contracts` CI job
// (`scripts/sim-contracts`), which boots the simulator and the backend and passes their addresses. Outside `build`
// by construction: this task is never a dependency of `check`. It FAILS — never skips — when an address is missing,
// so a job that forgot to pass one cannot pass with nothing run.
val journeys: SourceSet by sourceSets.creating
dependencies {
    "journeysImplementation"(project(":test:control"))
    "journeysImplementation"(kotlin("test-junit"))
    "journeysImplementation"(libs.coroutines.core)
    "journeysImplementation"(libs.kotlinx.serialization.json)
    "journeysImplementation"(libs.ktor.client.cio)
}
tasks.register<Test>("journeys") {
    description = "The all-real journeys against one simulator app and a local backend (ios-contracts only)."
    group = "verification"
    testClassesDirs = journeys.output.classesDirs
    classpath = journeys.runtimeClasspath
    useJUnit()
    // Journeys wait on real simulators and a real backend; each wait states its own bound.
    outputs.upToDateWhen { false }
    listOf("appA", "backend").forEach { name ->
        providers.gradleProperty("snapsync.journey.$name").orNull?.let { systemProperty("snapsync.journey.$name", it) }
    }
}

tasks.test {
    // JUnit 4: the contracts module (on the runtime path through the rig) binds kotlin-test to JUnit 4 in its main
    // code, and two kotlin-test framework bindings cannot coexist (the same reason `:test:control` gives).
    useJUnit()
}
