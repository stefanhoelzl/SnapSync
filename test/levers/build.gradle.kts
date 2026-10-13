plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("snapsync.targets")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
}

kotlin {
    // The Android rig release is minified: the descriptor lookup reaches the generated stubs by name.
    extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget> {
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.rpc.core)
            api(libs.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // The world's events carry what the app showed: the screen's vocabulary, as the rig serves it.
            api(project(":ui:state"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
