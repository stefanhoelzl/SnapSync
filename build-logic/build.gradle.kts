plugins {
    `kotlin-dsl`
}

// The plugin's classes load into the Gradle daemon, so they target the daemon's JVM (21), not the app's `jdk`.
kotlin {
    jvmToolchain(21)
}

dependencies {
    // compileOnly, deliberately: the plugin runs against the ONE Kotlin Gradle Plugin the root build already loaded
    // (`apply false` there). A second KGP on this build's runtime classpath would load on its own classloader, and
    // the Apple targets' global build services then fail to cast across the two — the failure the root's comment
    // records.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
}

gradlePlugin {
    plugins {
        register("targets") {
            id = "snapsync.targets"
            implementationClass = "app.snapsync.buildlogic.TargetsPlugin"
        }
    }
}
