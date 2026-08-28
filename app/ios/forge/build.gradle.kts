plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

// The FORGE binary: the real `StatusScreen` over forged sources, for the marketing screenshots.
//
// It is a separate module and a separate Xcode target because that is the only way to contain it. Forge
// used to be a *mode* of `:app:ios` — a `CompositionMode.Forge` case, a `ForgeShell` delegate implementing
// ~15 `Shell` members whose job was to make every OS entry point inert, and a branch in the shell's one
// mode switch. All of that SHIPPED, inert at runtime rather than absent, and it could not be gated away:
// `SnapSyncRoot.kt` named `ForgeShell` directly, so removing the source would leave the shell naming a
// type that no longer existed (spec `module-architecture`, "A build-time-only module is contained by
// compilation" — the clause about a surface reached through the shell's own switch).
//
// Here, inertness is not performed. This binary does not link `:app:ios` at all, so it has no
// `SnapSyncRoot`, no live graph, no App Attest, no ledger and no backend client. A forge process cannot
// boot the live stack because there is nothing in it to boot.
//
// Built ONLY under `-Psnapsync.forge=true`. Without it, `ForgeStatusHost.kt` is not on `:ui:presentation`'s
// compile path either, so the preset table stops shipping too.
// Gated exactly like its source. Without the property this module compiles NOTHING — the source directory
// is not added, so there is no forge entry point anywhere and `:ui:presentation` does not carry the preset
// table either. The module is still in `settings.gradle.kts` unconditionally, so the module set has one
// answer rather than a property-dependent one (`ModuleSetTest`).
val forgeEnabled = providers.gradleProperty("snapsync.forge").map(String::toBoolean).getOrElse(false)

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SnapSyncForgeKit"
            isStatic = true
        }
    }

    sourceSets {
        if (!forgeEnabled) {
            // Compile nothing. Setting the srcDirs to empty rather than leaving the default is deliberate:
            // a stale file under `src/iosMain` must not quietly become part of some other build.
            iosMain { kotlin.setSrcDirs(emptyList<String>()) }
        }
        iosMain.dependencies {
            // `:domain` for the model vocabulary the screen speaks, and the two UI modules. Deliberately
            // NOT `:app:ios`, and not the adapter modules: the whole point is that this binary cannot
            // reach a port implementation, so it cannot touch the network, the Keychain, or the ledger.
            api(project(":domain:model"))
            implementation(project(":ui:screens"))
            implementation(project(":ui:presentation"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
        }
    }
}
