package app.snapsync.buildlogic

import com.autonomousapps.DependencyAnalysisExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.extra
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * `snapsync.conventions`, the one plugin the root build file applies (`docs/architecture.md`, "The build"): what every
 * module of this build has the day it is included, and the root's own lifecycle. It configures every subproject from
 * the root, so no module opts in and none can forget to.
 *
 * The plugins it applies stay their own: `snapsync.quality` (every detekt task), `snapsync.archive-names` (archive
 * names from the module path), `snapsync.coverage` (every module's coverage) and `snapsync.diagrams` (the diagrams
 * under `architecture/`).
 */
class ConventionsPlugin : Plugin<Project> {
    override fun apply(root: Project) {
        check(root == root.rootProject) { "snapsync.conventions is the root's: it configures every subproject" }
        // `base` gives the ROOT project the lifecycle tasks (`check`, `build`) the root's gates ride —
        // `./gradlew build` includes the root `check`, so `detektAppShell` runs in the canonical build
        // (`docs/architecture.md`, "The shell gates").
        root.pluginManager.apply("base")
        root.pluginManager.apply("snapsync.quality")
        root.pluginManager.apply("snapsync.archive-names")
        root.pluginManager.apply("snapsync.coverage")
        root.pluginManager.apply("snapsync.diagrams")
        dependencyAnalysis(root)
        buildLogicTests(root)
        nativeThreads(root)
        val mac = System.getProperty("os.name").startsWith("Mac")
        root.subprojects {
            warningsAreErrors(this)
            fullTestFailures(this)
            iosPlatformTest(this)
            if (!mac) iosTestCompilesOff(this)
        }
    }

    /**
     * `./gradlew buildHealth` — declared-but-unused module dependencies (`docs/architecture.md`).
     *
     * Gradle prevents *using* a dependency that is not declared; nothing prevents *declaring* one that is not used, and
     * that gap is not academic here: a core module once declared another and imported nothing from it for months,
     * contradicting the documented dependency graph while every check stayed green.
     *
     * The root plugin only AGGREGATES; each subproject must apply it or `buildHealth` reports nothing and still
     * succeeds — a vacuous pass of exactly the kind the guards exist to prevent.
     *
     * NON-GATING on purpose: not wired into `check`, every issue `warn`, jvm/common edges only — the plugin has no
     * iOS-target support (upstream #1672); iosMain-only edges are covered by the text gates instead. The root build
     * file keeps the classpath force its metadata reader needs.
     */
    private fun dependencyAnalysis(root: Project) {
        root.pluginManager.apply(DEPENDENCY_ANALYSIS)
        root.extensions.configure(DependencyAnalysisExtension::class.java) {
            issues { all { onAny { severity("warn") } } }
        }
        root.subprojects { pluginManager.apply(DEPENDENCY_ANALYSIS) }
    }

    /**
     * The build logic's own tests (the zero coverage gate's judgement, the wire's routes, …): an included build's
     * `check` is never reached from this one's, so without this edge they would run on nobody's command.
     */
    private fun buildLogicTests(root: Project) {
        val tests = root.gradle.includedBuild(QualityPlugin.BUILD_LOGIC).task(":test")
        root.tasks.named("check").configure { dependsOn(tests) }
    }

    /**
     * KGP runs every Kotlin/Native link with 4 backend threads by default, and on a host with fewer cores the compiler
     * warns on every link (`The number of threads 4 is more than the number of processors 3` — GitHub's macos-26
     * runner has 3). So the count is min(cores, 4): the default's cap, never more threads than the host has. KGP reads
     * `kotlin.native.parallelThreads` from each project's extra properties; an explicit
     * `-Pkotlin.native.parallelThreads=` still wins.
     */
    private fun nativeThreads(root: Project) {
        if (root.providers.gradleProperty(NATIVE_THREADS).isPresent) return
        val threads = minOf(Runtime.getRuntime().availableProcessors(), KGP_NATIVE_THREADS)
        root.allprojects { extra[NATIVE_THREADS] = threads.toString() }
    }

    /**
     * Every Kotlin compilation fails on a warning, so a new one is fixed where it is introduced instead of joining a
     * backlog nobody reads (147 had accumulated in CI logs by 2026-09). A warning that is deliberate is silenced AT ITS
     * SITE (`@Suppress`, `@OptIn`) with the reason next to it, never here. Build scripts are held to the same rule by
     * `org.gradle.kotlin.dsl.allWarningsAsErrors` in gradle.properties.
     *
     * The shared-source-set METADATA compilations (`compile*KotlinMetadata`) are the one exemption, and it costs no
     * coverage: every source set they compile is also compiled by a platform compilation (jvm, iosArm64,
     * iosSimulatorArm64), which is strict, so a warning in `commonMain`/`iosMain` code still fails the build there.
     * What only the metadata compilation reports is the Kotlin/Native KLIB loader's "same unique_name found in more
     * than one library" — Compose Multiplatform puts both its `org.jetbrains.compose.*` and Google's
     * `androidx.compose.*` commonMain klibs on the iosMain metadata classpath, which no source change here can fix.
     */
    private fun warningsAreErrors(project: Project) {
        project.tasks.withType(KotlinCompilationTask::class.java)
            .matching { !it.name.endsWith("KotlinMetadata") }
            .configureEach { compilerOptions.allWarningsAsErrors.set(true) }
    }

    /**
     * Every test task — JVM `Test`, the Kotlin/Native simulator's — prints a failure in Gradle's FULL format: the
     * assertion's message and its stack down to the test. SHORT, Gradle's default, prints the exception's class and
     * the first frame in the test class, which for a `behaviour { … }` is the `= behaviour {` line: CI then showed
     * `java.lang.AssertionError at EventChecksIntegrationTest.kt:33` and no `expected:<3> but was:<5>`. The simulator
     * runner is worse still — `AssertionError at null:-1` — and a red iOS-only test cannot be re-run from Linux. A
     * module's own `testLogging` adds to this (the simulator modules turn standard streams on) and does not restate it.
     */
    private fun fullTestFailures(project: Project) {
        project.tasks.withType(AbstractTestTask::class.java).configureEach {
            testLogging { exceptionFormat = TestExceptionFormat.FULL }
        }
    }

    /**
     * Platform-bound tests (`docs/testing.md`, "Where each test runs"). The shared `commonTest` runs ONCE, on the JVM,
     * under `build`. A platform runtime runs only what only it can answer: a module's `iosTest` (the iOS adapters and
     * their contract bindings on the simulator's `test.kexe`, the native SQLite drivers) through
     * `./gradlew iosPlatformTest` (macOS), and a module's `androidDeviceTest` through `./gradlew androidPlatformTest`
     * (`snapsync.android` registers that one, beside the managed device it runs on). Derived from the source tree,
     * never listed: a module that gains iOS tests is run the day it does.
     */
    private fun iosPlatformTest(project: Project) {
        val sources = listOf("src/iosTest", "src/iosSimulatorArm64Test")
        if (sources.none { project.file(it).isDirectory }) return
        project.tasks.register("iosPlatformTest") {
            group = "verification"
            description = "Runs this module's iOS tests on the simulator."
            dependsOn("iosSimulatorArm64Test")
        }
    }

    /**
     * Off macOS no simulator runs an iOS test, yet `build` compiled every module's test klibs for both iOS targets (37
     * Kotlin/Native compiles on Linux, linked and run never). `ios-build`'s `iosPlatformTest` compiles and runs the iOS
     * tests on a Mac, so on any other host their compiles are switched off; the main iOS compiles stay, so iOS-only
     * breakage in production code still fails `build` (the Linux-runnable proxy, AGENTS.md).
     */
    private fun iosTestCompilesOff(project: Project) {
        project.tasks.matching { IOS_TEST_COMPILE.matches(it.name) }.configureEach { enabled = false }
    }

    private companion object {
        const val DEPENDENCY_ANALYSIS = "com.autonomousapps.dependency-analysis"
        const val NATIVE_THREADS = "kotlin.native.parallelThreads"

        /** KGP's default backend thread count for a Kotlin/Native link: the cap [nativeThreads] keeps. */
        const val KGP_NATIVE_THREADS = 4

        val IOS_TEST_COMPILE = Regex("""compileTestKotlinIos\w*|link\w*TestIos\w*""")
    }
}
