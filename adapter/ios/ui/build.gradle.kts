plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

// The iOS app's user interface and foreground life (`docs/architecture.md`): the `Ui` adapter — the Compose scene
// SwiftUI hosts, and the scene rule that decides when one exists — and the `Lifecycle` adapter, which share one
// `SceneRecord`. App-only: the extension has no screen and no foreground, so nothing it links may reach this module.
kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            implementation(project(":domain:presentation"))
            implementation(project(":ui:screens"))
            implementation(project(":ui:components"))
            implementation(project(":adapter:ios:ext-safe"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
