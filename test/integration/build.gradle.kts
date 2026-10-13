import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}
// INSTRUMENTED, credited to the three WIRING modules only (`docs/architecture.md`, "Coverage"): `:domain:compose`,
// `:domain:host` and `:app:jvm`, through the root build file's crediting edges. The wiring graph is not unit-tested by
// law ("One shared composition") and is smoke-tested here, so this suite is the test written for it. For every other
// module it runs, it counts for nothing: a module's report is filtered to its own classes, and only those three name
// this module as a producer — so a thick integration suite still cannot stand in for a thin unit suite.
//
// The `journeys` task is NOT instrumented: the all-real journeys run outside `build`, against a simulator app or an
// emulator, and a report must never trigger them. `disabledForTestTasks` keeps both their data and their run out.
kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("journeys")
        }
    }
}

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
    testImplementation(project(":ui:state"))
    testImplementation(project(":domain:presentation"))
    testImplementation(project(":domain:feature"))
    testImplementation(libs.kotlinx.datetime)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
}

// ---- The all-real journeys ----
// (`docs/testing.md`, "All-real journeys are the contracts' safety net")
//
// A few end-to-end runs with EVERY system real: the rig build of the app on ONE simulator or emulator, the real
// backend served locally, the real photo library. The second member is played by the journey itself, over the backend's
// public HTTP surface with real JPEG bytes (`Member`), so to the app it is a foreign member like any device.
// Written against the same typed client as the tests above, and run ONLY by the `journeys (ios|android)` CI jobs
// (`scripts/sim-contracts`, `scripts/android-journeys`), which boot the device and the backend and pass their
// addresses. Outside `build` by construction: this task is never a dependency of `check`. It FAILS — never skips —
// when an address is missing, so a job that forgot to pass one cannot pass with nothing run.
val journeys: SourceSet = sourceSets.create("journeys")
dependencies {
    "journeysImplementation"(project(":test:control"))
    "journeysImplementation"(project(":domain:model"))
    // The version header's one name, generated from `api/openapi.yaml`.
    "journeysImplementation"(project(":domain:wire"))
    "journeysImplementation"(project(":ui:state"))
    "journeysImplementation"(project(":domain:presentation"))
    "journeysImplementation"(project(":domain:feature"))
    "journeysImplementation"(libs.kotlinx.datetime)
    "journeysImplementation"(kotlin("test-junit"))
    "journeysImplementation"(libs.coroutines.core)
    "journeysImplementation"(libs.kotlinx.serialization.json)
    "journeysImplementation"(libs.ktor.client.cio)
}
tasks.register<Test>("journeys") {
    description = "The all-real journeys against one simulator or emulator app and a local backend (CI journeys only)."
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

// The journeys' Android motion photos: REAL camera files — a Pixel 6 Pro's and a Pixel 8a's (the second carries
// two video tracks), and Samsung One UI 5 and 6 JPEGs (their videos carry sound) and a One UI 6 HEIC — from
// Immich's test assets. They are AGPL-3.0, so they are FETCHED, never committed: pinned to one commit, each
// checked against its SHA-256, into the build directory, and added to the journeys' resources under
// `motionphoto/`. A changed or missing file fails the build naming it; nothing falls back. (The iPhone Live Photo
// is our own capture, committed under `moving/`.)
abstract class FetchFixtures : DefaultTask() {
    /** Each file's name → `url sha256`. */
    @get:Input abstract val files: MapProperty<String, String>

    @get:OutputDirectory abstract val into: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dir = into.get().dir("motionphoto").asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        files.get().forEach { (name, spec) ->
            val (url, sha256) = spec.split(' ')
            val bytes = URI(url).toURL().openStream().use { it.readBytes() }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            check(actual == sha256) { "$name from $url has SHA-256 $actual, pinned $sha256" }
            dir.resolve(name).writeBytes(bytes)
        }
    }
}
val immichAssets = "https://raw.githubusercontent.com/immich-app/test-assets/ec56cc6c1d76c45bc9615dc6ea232470a2b0a3a1"
val fetchMotionPhotos = tasks.register<FetchFixtures>("fetchMotionPhotos") {
    description = "Fetches the journeys' real Android motion photos (Immich test assets, pinned by commit and SHA-256)."
    files.set(
        mapOf(
            "pixel-6-pro.jpg" to "4944eb4bb29ebf15dc342ab6d56399ab32efdfa78612bf08a9f53b1338c324bc",
            "pixel-8a.jpg" to "c0f8317d1c2fca83df19aa2108d4754faf70d60a1baab60f7220a5d463dad1d0",
            "samsung-one-ui-5.jpg" to "abfa7937af1c3dc2e4321dd713c7041c6871c21772d671d065b059ec07779537",
            "samsung-one-ui-6.jpg" to "d193cbfc1967b8c319569fdb3c6e0c3ec5a5ebbe4f47080e9c6a86e8c6d327b3",
            "samsung-one-ui-6.heic" to "4a604f5f28dee41565f766585ab4b29026e0031389504a0d2152a3b844a7e19c",
        ).mapValues { (name, sha256) -> "$immichAssets/formats/motionphoto/$name $sha256" },
    )
    into.set(layout.buildDirectory.dir("journeys-fixtures"))
}
journeys.resources.srcDir(fetchMotionPhotos)

// The journeys' full runtime classpath, one line, so `scripts/sim-contracts` and `scripts/android-journeys` can run
// them with a bare `java` next to a live simulator instead of starting Gradle there: a Gradle daemon plus a test JVM
// pushed the 7 GB CI runner into swap at exactly that moment, and the app missed its 5 s HTTP timeout on a request
// the backend had answered in 132 ms (run 36173548419).
val journeysClasspath = tasks.register("journeysClasspath") {
    description = "Writes the journeys' runtime classpath to build/journeys-classpath.txt (CI journeys only)."
    val classpath = journeys.runtimeClasspath
    val out = layout.buildDirectory.file("journeys-classpath.txt")
    inputs.files(classpath)
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.asPath) }
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
}
