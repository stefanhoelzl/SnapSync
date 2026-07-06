plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // Capability `photo-date-cutoff`: "now" + local→UTC conversion for the capture-date cutoff.
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmMain.dependencies {
            // JVM-only: the authoritative QR generator (Gradle `generateConfigQr` task). ZXing is
            // not on the app's runtime path.
            implementation(libs.zxing.core)
        }
    }
}

// The authoritative QR generator: runs the jvmMain main() so the encoder stays in lockstep with
// the app's decoder. Reads the event id from env / gitignored local.properties.
val generateConfigQr by tasks.registering(JavaExec::class) {
    group = "snapsync"
    description = "Encode an event id into a snapsync:// deeplink and render a QR PNG."
    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider)
    classpath(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("app.snapsync.config.QrGeneratorMainKt")
    // Read local.properties / write build output relative to the repo root.
    workingDir = rootDir
}
