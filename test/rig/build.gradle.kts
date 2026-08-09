plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The dev/test CONTROL CHANNEL (`:test:rig`) — an HTTP server that runs INSIDE the iOS app so an agent
// can force OS-callback entry points and read live state over `usbmux forward`. Dev infrastructure:
// non-gating, NO SPEC — the same posture as `:test:harness-driver` and `ssh-mac.yml`, and for the same
// reason it states: every surface here is a mechanical projection of a contract specified elsewhere
// (`/state` is a compiler-generated encoder over the real `UiState`, `/trigger` invokes the real
// `@PlatformEntry` members, `/logs` passes `DeviceLogSource.tail` through verbatim), so there is no
// second way-to-drive that can rot or lie. Decision record:
// `openspec/changes/.../add-rig-control-channel/design.md`.
//
// WITHHOLDING ARGUMENT (`module-architecture`, "The module set withholds; packages organize"): this is
// the ONLY module permitted to depend on `ktor-server-*`. A server import anywhere else is a compile
// error — which is what makes this a module rather than a package.
//
// CONTAINMENT IS COMPILE-TIME. `:app:ios` links this module, and adds `src/hook/` to its own iosMain
// source set, ONLY under `-Psnapsync.rig=true`. A production build contains no source from here at all —
// not a stub, not an inert branch. That is why no `ios-app-shell` requirement changes: nothing shipped
// can observe this module or the env var its hook reads.
//
// NO TESTS, deliberately, matching `:test:harness-driver`. The condition that makes that honest is that
// this module holds no projection it could get wrong; if that ever stops being true it needs a `jvm()`
// target and tests with it (`:test:world`'s `World.core` is the same `AppCore`, so that path is open).
kotlin {
    // iOS only — this module is linked into the device app, and nothing else consumes it. No `jvm()`:
    // see the module note above.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // The rig is written against `:domain` types only (AppCore, the read-model StateFlows) plus
            // the presentation read-model it serializes. Platform-bound verbs arrive as injected lambdas
            // the host shell builds — the same shape `flow/` uses for port touches — so this module names
            // no platform API and an Android target would be a build-file edit, not a rewrite.
            api(project(":domain"))
            implementation(project(":ui:presentation"))
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
        }
    }
}
