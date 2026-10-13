plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    // The end-to-end tests drive the JVM host over the real backend too (`liveEdge.consumedBy`, below).
    id("snapsync.live-edge")
}

// `:test:cli` — `rig`, the control channel's typed services from a shell (`docs/testing.md`, "Typed services"). Its
// commands are DERIVED from the services `:test:levers` declares — each one's kotlinx-rpc descriptor names its methods,
// their parameters and types — so a service or method added there is a command here with no edit.
//
// Support group (`docs/architecture.md`, "The module set withholds; packages organize"): it links into no
// shipped-format binary. Its own module, not `:test:control`'s, so a call starts on the levers and kRPC's client alone
// rather than on the domain `:test:control` compiles against.
//
// Installed with `./gradlew :test:cli:installDist`, as `test/cli/build/install/rig/bin/rig` (`rig-channel` skill).
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    // Every command is read off a kotlinx-rpc descriptor, which is experimental API throughout.
    compilerOptions.optIn.add("kotlinx.rpc.internal.utils.ExperimentalRpcApi")
}

application {
    applicationName = "rig"
    mainClass = "app.snapsync.cli.MainKt"
}

dependencies {
    // The services the commands are derived from, and their value types the arguments decode into.
    implementation(project(":test:levers"))
    // The transport the tests' client uses: kRPC over a Ktor WebSocket.
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.rpc.krpc.ktor.client)
    implementation(libs.kotlinx.rpc.krpc.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    // The JVM host the end-to-end tests drive the CLI against.
    testImplementation(project(":test:rig"))
}

// The install runs on the build's own JDK: the classes are its bytecode, which the machine's default `java` may be too
// old to load. The install is this machine's, never shipped, so the path is baked in.
val toolchainHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get()) }
    .map { it.metadata.installationPath.asFile.absolutePath }
tasks.startScripts {
    inputs.property("toolchainHome", toolchainHome)
    doLast {
        val home = toolchainHome.get()
        unixScript.writeText(unixScript.readText().replaceFirst("\n", "\nJAVA_HOME='$home'\n"))
    }
}

tasks.test {
    // JUnit 4, not the platform: the contracts module (on the runtime path through the rig) binds kotlin-test to
    // JUnit 4 in its main code, and two kotlin-test framework bindings cannot coexist.
    useJUnit()
}
liveEdge.consumedBy(tasks.test)
