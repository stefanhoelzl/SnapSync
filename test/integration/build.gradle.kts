plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}
// INSTRUMENTED, credited to the three WIRING modules only (`docs/architecture.md`, "Coverage"): `:domain:compose`,
// `:domain:host` and `:app:jvm`, through the root build file's crediting edges. The wiring graph is not unit-tested by
// law ("One shared composition") and is smoke-tested here, so this suite is the test written for it. For every other
// module it runs, it counts for nothing: a module's report is filtered to its own classes, and only those three name
// this module as a producer — so a thick integration suite still cannot stand in for a thin unit suite.

// The seam-to-UI-state integration surface (`docs/testing.md`, "The seam-to-UI-state
// integration surface"). Every test starts the control channel's JVM host in-process — the app the JVM root composes
// by the same shared host composition the iOS shell calls, over the mocks — and drives it through the protocol's
// typed client (`:test:control`) ONLY. So a test names no mock, port, flow or composition type, and one test
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
    // Named explicitly, not received through the client: the core's zones export nothing transitively.
    testImplementation(project(":domain:model"))
    testImplementation(project(":domain:domain"))
    // An invite link's text and an event key's: the codecs the app shares them in.
    testImplementation(project(":domain:format"))
    testImplementation(project(":ui:state"))
    testImplementation(project(":domain:presentation"))
    testImplementation(project(":domain:feature"))
    testImplementation(libs.kotlinx.datetime)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
}

// ---- The marketing screenshots ----
// (`docs/deployment.md`, "Screenshots")
//
// The capture of the six raws from the REAL app: the rig build on a simulator over launch adapters, driven to each
// shot by the scenarios in the test source set (`Shots.kt`, which `ShotsTest` runs on the JVM host on every build),
// and captured with `simctl`. Run ONLY by `screenshots.yml` on a macOS runner, as a bare JVM on the classpath
// below — the same reason the journeys are. Never a dependency of `check`.
val screenshots: SourceSet = sourceSets.create("screenshots") {
    compileClasspath += sourceSets.test.get().output + sourceSets.test.get().compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets.test.get().runtimeClasspath
}
val screenshotsClasspath = tasks.register("screenshotsClasspath") {
    description = "Writes the screenshot capture's runtime classpath to build/screenshots-classpath.txt."
    val classpath = screenshots.runtimeClasspath
    val out = layout.buildDirectory.file("screenshots-classpath.txt")
    inputs.files(classpath)
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.asPath) }
}

tasks.test {
    // JUnit 4: the contracts module (on the runtime path through the rig) binds kotlin-test to JUnit 4 in its main
    // code, and two kotlin-test framework bindings cannot coexist (the same reason `:test:control` gives).
    useJUnit()
    // Every test starts its own in-process rig host on an OS-chosen loopback port over in-memory mocks, so test classes
    // share nothing and split across JVMs. Run in one, the suite was the build's longest task, and it depends on
    // nearly every module, so nothing overlapped it: 276 s in one fork against 73 s in four (2026-10-13). Half the
    // cores, at most four: each fork is a full composed app.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    // How the host's lanes take turns (`docs/testing.md`, "How a test waits"): `turns` by default, `shuffled:<seed>` to
    // replay one order, `parallel` to hunt a race — `-Psnapsync.lanes=…`.
    providers.gradleProperty("snapsync.lanes").orNull?.let { systemProperty("snapsync.lanes", it) }
}
