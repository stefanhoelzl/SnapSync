import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// `:test:journeys` — the device runs of the `journeys (ios|android)` CI jobs (`docs/testing.md`, "All-real journeys are
// the contracts' safety net"): the program that runs them, and the journeys it runs.
//
// - `main`: the program — `journeys <command>` — over the typed client (`:test:client`). Run as a bare JVM from its
//   installed start script, never by Gradle: a Gradle daemon and a test JVM beside the live simulator pushed the 7 GB
//   macOS runner into swap, and the app missed its 5 s HTTP timeout on a request the backend had answered in 132 ms
//   (run 36173548419; decision record `changes/archive/2026-09-25-one-simulator-journeys`, D5).
// - `journeys`: the all-real journeys — A, the rig build of the app on ONE simulator or emulator; the real backend,
//   served locally; the second member played over the backend's public HTTP surface (`Member`) — and the entry point
//   that runs them in the program's own JVM through JUnit. Compiled by `build`, run only by the program.
// - `test`: the program's own tests, run by `build`.
//
// Install it with the app's `-Psnapsync.deployment` (`local`), in the same Gradle call as the app, as
// `scripts/journeys-build` does: the journeys read A's invite with the link origin `:domain:model` generates from the
// deployment, and a program installed for another one cannot decode the invite the app shares.
//
// Support group (`docs/architecture.md`, "The module set withholds; packages organize"): it links into no
// shipped-format binary.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

val journeys: SourceSet = sourceSets.create("journeys") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[journeys.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[journeys.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    implementation(project(":test:client"))
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    "journeysImplementation"(project(":test:control"))
    "journeysImplementation"(project(":domain:model"))
    "journeysImplementation"(project(":domain:domain"))
    // An invite link's text and an event key's: the codecs the app shares them in.
    "journeysImplementation"(project(":domain:format"))
    // The version header's one name, generated from `api/openapi.yaml`.
    "journeysImplementation"(project(":domain:wire"))
    "journeysImplementation"(project(":ui:state"))
    "journeysImplementation"(project(":domain:presentation"))
    "journeysImplementation"(project(":domain:feature"))
    "journeysImplementation"(libs.kotlinx.datetime)
    "journeysImplementation"(kotlin("test-junit"))
    "journeysImplementation"(libs.ktor.client.cio)
    // The member attests as a device does, with the software key the local backend's `any` trust accepts.
    "journeysImplementation"(project(":test:contracts"))
    "journeysImplementation"(project(":domain:ports"))
    "journeysImplementation"(project(":adapter:generic:app"))

    testImplementation(kotlin("test"))
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

// The program's start script runs the JOURNEYS' classpath: the journeys run in the program's own JVM, so no second
// JVM sits beside the simulator, while `main` itself compiles against no test framework. 512 MB, as the bare `java`
// the journeys ran in before.
val journeysJar = tasks.register<Jar>("journeysJar") {
    archiveClassifier.set("journeys")
    from(journeys.output)
}
application {
    applicationName = "journeys"
    mainClass = "app.snapsync.journeys.MainKt"
    applicationDefaultJvmArgs = listOf("-Xmx512m")
}
val journeysRuntime = configurations[journeys.runtimeClasspathConfigurationName]
// The program runs on the build's own JDK: the classes are its bytecode, which the machine's default `java` may be too
// old to load. The install is this machine's, never shipped, so the path is baked in (as `rig`'s is).
val toolchainHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get()) }
    .map { it.metadata.installationPath.asFile.absolutePath }
tasks.startScripts {
    classpath = files(tasks.jar, journeysJar) + journeysRuntime
    inputs.property("toolchainHome", toolchainHome)
    doLast {
        val home = toolchainHome.get()
        unixScript.writeText(unixScript.readText().replaceFirst("\n", "\nJAVA_HOME='$home'\n"))
    }
}
distributions.main {
    contents {
        from(journeysJar) { into("lib") }
        from(journeysRuntime) { into("lib") }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}
// Installed on demand (`installDist`), never archived: `build` compiles the journeys, but needs no zip of the program.
tasks.distZip { enabled = false }
tasks.distTar { enabled = false }
tasks.named("check") { dependsOn(tasks.named(journeys.getCompileTaskName("kotlin"))) }

tasks.test {
    // JUnit 4, as the journeys run: the contracts module (on their path) binds kotlin-test to JUnit 4 in its main code.
    useJUnit()
}
