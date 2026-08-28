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
// NO TESTS — and that is now an EXCEPTION rather than a consequence, which is worth stating plainly
// because the file used to justify it and the justification has lapsed.
//
// The old wording was: "this module holds no projection it could get wrong; if that ever stops being true
// it needs a `jvm()` target and tests with it". That condition is FALSE as of the launch-trigger
// retirement. `src/iosMain/` now holds real behaviour it could get wrong — the seeder's above/below-floor
// alternation, its platform-forced chunk sizes, the wiper's scope grammar and its fetch selection — and
// none of it is tested anywhere.
//
// The exception was taken deliberately (decision record: `…-retire-launch-env-triggers` D9). The
// alternative on the table was pushing those decisions into `:domain model/` to keep this module a lens,
// and that was rejected because it puts more dev vocabulary into the module that SHIPS, which is the
// opposite of what that change is for. So the cost lands here instead, and it is real: the six pinned
// `detektAppShell` suppressions this code used to carry are gone because the gate stopped scanning it,
// not because the decisions moved.
//
// What keeps that honest is the blast radius rather than a test: this code runs only on a device an
// operator is deliberately driving, and its two dangerous verbs answer with what they did rather than
// logging it. If that stops being true — if anything here is ever composed into a path an operator did not
// ask for — it needs a `jvm()` target and tests with it (`:test:world`'s `World.core` is the same
// `AppCore`, so that path is open).
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
            api(project(":domain:model"))
            api(project(":domain:ports"))
            api(project(":domain:compose"))
            implementation(project(":ui:presentation"))
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
            implementation(libs.kotlinx.datetime)
        }
    }
}
