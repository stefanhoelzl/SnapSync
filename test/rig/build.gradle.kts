plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The dev/test CONTROL CHANNEL (`:test:rig`) — an HTTP server that runs INSIDE the iOS app so an agent
// can force OS-callback entry points and read live state over `usbmux forward`. Its protocol is specified
// (`docs/testing.md`, "One control protocol, served by two hosts") and its JVM host is tested in
// the canonical check; beyond that it is honest for the reason it always was: every surface is a projection
// of a contract specified elsewhere
// (`/state` is a compiler-generated encoder over the real `UiState`, `/trigger` invokes the real
// `@PlatformEntry` members, `/logs` passes `DeviceLogSource.tail` through verbatim), so there is no
// second way-to-drive that can rot or lie. Decision record:
// `openspec/changes/.../add-rig-control-channel/design.md`.
//
// WITHHOLDING ARGUMENT (`docs/architecture.md`, "The module set withholds; packages organize"): this is
// the ONLY module permitted to depend on `ktor-server-*`. A server import anywhere else is a compile
// error — which is what makes this a module rather than a package.
//
// CONTAINMENT IS COMPILE-TIME. `:app:ios` links this module, and adds `src/hook/` to its own iosMain
// source set, ONLY under `-Psnapsync.rig=true`. A production build contains no source from here at all —
// not a stub, not an inert branch. That is why no `sync-status` requirement changes: nothing shipped
// can observe this module or the env var its hook reads.
//
// TWO HOSTS, ONE PROTOCOL (`docs/testing.md`, "One control protocol, served by two hosts").
// `commonMain` is the server, the routes, the state projection, the closed verb vocabulary and every command
// table both hosts share. `iosMain` + `src/hook/` are the app host (above). `jvmMain` is the JVM host: a
// `World` from `:test:world` — whose `core` is the real `AppCore` from the same `snapSyncApp` — handed to the
// unchanged server through its own hook. The JVM target links into no shipped-format binary; only test
// equipment consumes it (`docs/architecture.md`, "The module set withholds; packages organize").
//
// TESTS. `commonMain` is tested now, through the JVM host, by `:test:control` (the protocol's typed client),
// which ends the no-tests exception `…-retire-launch-env-triggers` D9 took for this module. The exception that
// remains is narrower and still deliberate: the iOS seeder and wiper (`src/iosMain/.../gallery/`) are
// PhotoKit by nature and run only on a device or simulator an operator is driving, so nothing tests them.
// If that code is ever composed into a path an operator did not ask for, it needs tests.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    // The JVM host (see the module note above). Never linked into a shipped binary.
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // The rig is written against `:domain` types only (AppCore, the read-model StateFlows) plus
            // the presentation read-model it serializes. Platform-bound verbs arrive as injected lambdas
            // the host shell builds — the same shape `flow/` uses for port touches — so this module names
            // no platform API and an Android target would be a build-file edit, not a rewrite.
            // The port contracts it can run in-app on a device (`docs/architecture.md`), and the
            // refusal marker the `/contract` route answers 409 on. Both modules are contained the same
            // way: linked only under `-Psnapsync.rig=true`.
            //
            // All `implementation`, never `api`: the protocol's JVM client (`:test:control`) compiles against
            // this module, and what it may reach is exactly what is on ITS compile path — the wire types here,
            // plus the read-models it declares itself. An `api` edge would hand it the ports and the composition
            // (`docs/architecture.md`, "The module set withholds; packages organize").
            implementation(project(":test:contracts"))
            implementation(project(":domain:model"))
            implementation(project(":domain:ports"))
            implementation(project(":domain:compose"))
            // The device-log writer the rig re-points at the extension's log for an invoked cycle.
            implementation(project(":domain:services"))
            implementation(project(":domain:presentation"))
            // `StatusContainerHost` is an Orbit `ContainerHost`; presentation does not export Orbit.
            implementation(libs.orbit.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
        }

        // The iOS half — the `/device` verbs and the gallery read. This is where the module stopped being
        // platform-free: `commonMain` still names no platform API, so a second platform brings its own
        // `iosMain` equivalent rather than a rewrite, but the seeder and the wiper are PhotoKit by nature.
        //
        // `:adapter:ios:app-only` for the photo-access port impl the wipe must ask through, and
        // `:adapter:ios:ext-safe` for the App-Group directory the identity fallback is planted into. Both
        // are already on `:app:ios`'s compile path, so neither widens what a rig build links — and neither
        // is reachable from a build without `-Psnapsync.rig=true`, which links none of this module.
        iosMain.dependencies {
            implementation(project(":adapter:ios:app-only"))
            implementation(project(":adapter:ios:ext-safe"))
        }

        // The JVM host: the world it composes (and, through the world's own JVM half, the real backend).
        jvmMain.dependencies {
            implementation(project(":test:world"))
            implementation(project(":domain:feature"))
        }
    }
}

// `./gradlew :test:rig:runJvmHost [-Psnapsync.rigBackend=mini|deno] [-Psnapsync.rigPort=N]` — the JVM host for
// an agent to drive by hand. Prints one `RIG-JVM READY <port>` line once bound, then serves until killed.
// Tests do not use this: they start a host in-process (`JvmRigHost.start`).
val apiDir = rootProject.layout.projectDirectory.dir("api")
tasks.register<JavaExec>("runJvmHost") {
    group = "application"
    description = "Serves the rig control protocol over a World on loopback (the JVM host)."
    val jvmCompilation = kotlin.jvm().compilations.getByName("main")
    classpath = files(jvmCompilation.output.allOutputs, jvmCompilation.runtimeDependencyFiles)
    mainClass.set("app.snapsync.rig.JvmRigHostMainKt")
    dependsOn(":test:edge:resolveLocalDeployment")
    systemProperty("snapsync.rigBackend", providers.gradleProperty("snapsync.rigBackend").getOrElse("mini"))
    systemProperty("snapsync.rigPort", providers.gradleProperty("snapsync.rigPort").getOrElse("0"))
    systemProperty("snapsync.apiDir", apiDir.asFile.absolutePath)
    systemProperty("snapsync.liveEdgeStore", layout.buildDirectory.dir("live-edge").get().asFile.absolutePath)
}
