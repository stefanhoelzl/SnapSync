plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm {
        testRuns["test"].executionTask.configure {
            // Skiko loads native libs via a restricted method; future JDKs block it by default.
            jvmArgs("--enable-native-access=ALL-UNNAMED")
            // Compose's test renderer draws offscreen; headless skips AWT's display probe so the
            // tests need no X server on Linux (no Xvfb, no stale-lock hang).
            jvmArgs("-Djava.awt.headless=true")
        }
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            api(project(":ui:presentation"))
            implementation(project(":ui:components"))
            implementation(compose.runtime)
            implementation(compose.foundation)
        }
        // The screen tests live in commonTest, so they run on BOTH the JVM (fast loop, offscreen —
        // see the jvm block above) and iosSimulatorArm64 (`ios-test` in CI). That is the standing rule
        // — "every unit test runs on the iOS simulator too" — and it bites hardest here: iOS renders
        // these screens through a different Compose backend than the desktop one, so a JVM-only suite
        // never sees the target that ships.
        commonTest.dependencies {
            implementation(kotlin("test"))
            // The multiplatform `runComposeUiTest` API (no JUnit4 rule — that artifact is JVM-only).
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        jvmTest.dependencies {
            // Skiko's desktop native binaries — the JVM renderer the offscreen scene draws into.
            implementation(compose.desktop.currentOs)
        }
    }
}
