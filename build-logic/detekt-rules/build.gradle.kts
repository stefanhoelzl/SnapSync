plugins {
    // No version: the Kotlin plugin `kotlin-dsl` already put on this build's classpath, for the plugin project. A
    // second Kotlin plugin version in one build is an error.
    kotlin("jvm")
}

// The project's own detekt rules (`docs/architecture.md`, "Localization"), loaded by every detekt task
// `snapsync.quality` registers, by these coordinates (the root build substitutes them to this project). detekt runs
// them inside its own Kotlin runtime, so they are compiled against `detekt-api` only, keep to the plain stdlib, and
// target the JVM detekt runs on.
group = "app.snapsync.buildlogic"

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
