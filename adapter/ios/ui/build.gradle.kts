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
            api(project(":domain:inbound"))
            // The screen's vocabulary the Ui port is bound to here (`Ui<UiState, UiIntent>`); never presentation.
            implementation(project(":ui:state"))
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
            // The Lifecycle adapter's contract binding (`docs/architecture.md`).
            implementation(project(":test:contracts"))
            implementation(libs.kermit)
            // The Ui adapter's binding: the status screen's tour (`test/ui-tour`), over a real status container and the
            // platform's own date formatting, tapped through a Compose UI test.
            implementation(project(":adapter:ios:app-only"))
            implementation(project(":domain:presentation"))
            implementation(project(":domain:feature"))
            implementation(libs.kotlinx.datetime)
            implementation(libs.coroutines.core)
            implementation(libs.compose.ui.test)
            implementation(libs.compose.components.resources)
            implementation(libs.orbit.core)
        }
        // The status screen's tour, shared with the Android Ui adapter's binding.
        commonTest { kotlin.srcDir("../../../test/ui-tour/kotlin") }
    }
}
