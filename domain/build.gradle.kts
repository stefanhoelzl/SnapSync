import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

// The `:domain` core (spec `module-architecture`): one module, ZERO project() dependencies, no
// `iosMain` source directory (the targets exist so iosMain elsewhere can compile against it; the
// module itself is platform-free). Zones live as packages under src/*/kotlin — `model/` and
// `ports/` born in migration step 3a; `feature/`, `flow/`, `compose/` follow in later steps. The
// zone import laws are enforced by the self-arming gates in `:test:architecture`
// (capability `architecture-guards`).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    // Coverage measurement (capability `coverage-bounds`). Applied here rather than in a
    // `subprojects {}` block so the instrumented set is readable per module.
    alias(libs.plugins.kover)
}
// Coverage (capability `coverage-bounds`). The report is filtered to this module's OWN classes.
// The crediting edge that lets `:adapter:generic:fake`'s feature tests count toward this module is
// declared in the ROOT build file, not here: `ModuleSetTest` asserts this file names no module at
// all, because that absence is the precondition for the platform-free compile error.
kover {
    reports {
        filters {
            includes {
                projects.add(":domain")
            }
            // The composition root is OUTSIDE the measurable set (capability `coverage-bounds`,
            // "A composition root is outside the measurable set"). `module-architecture`'s
            // "One shared composition" says the wiring graph shall not be unit-tested, and that law
            // is the written form of a fact: a composition root is reachable only by composing it,
            // and any test that composes the whole graph is not a unit test. Measured at 1.13% here.
            // Excluded by ZONE, not by module, so `:domain`'s other eleven packages stay bounded.
            excludes {
                packages("app.snapsync.compose")
            }
        }
    }
}


// Full failure messages in CI: the Kotlin/Native simulator runner otherwise prints a terse
// "AssertionError at null:-1" with no expected/actual, which is useless for diagnosing
// platform-specific test failures.
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// The event link's origin, generated from the RESOLVED DEPLOYMENT (capability
// `deployment-configuration`) so the app, the backend, the xcconfig and the site all derive it from one
// declared value rather than each holding a copy. `snapsync.deployment` names WHICH deployment; the
// resolver renders it to `build/deployment.properties`, which Gradle reads natively.
//
// The resolver is invoked here at CONFIGURATION time because `LINK_ORIGIN` is generated into a source
// set. It is stdlib-only Python — the one runtime present on every runner and dev machine that also has
// Gradle (no CI job carries both Deno and Gradle, so the backend's runtime could not be the resolver).
val deploymentName: String = providers.gradleProperty("snapsync.deployment").get()

val resolvedDeployment: Map<String, String> = run {
    val repoRoot = rootProject.layout.projectDirectory.asFile
    providers.exec {
        workingDir(repoRoot)
        commandLine("python3", "scripts/resolve-deployment.py", deploymentName, "--quiet")
    }.result.get().assertNormalExitValue()
    val rendered = repoRoot.resolve("build/deployment.properties")
    check(rendered.isFile) { "the resolver produced no ${rendered.path} — cannot generate LINK_ORIGIN" }
    rendered.readLines()
        .filterNot { it.isBlank() || it.startsWith("#") }
        .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
}

val linkDomain: String = requireNotNull(resolvedDeployment["domain"]) {
    "deployment '$deploymentName' resolved no `domain` — the event link has no origin"
}

// ⚠️ The scheme is UNCONDITIONALLY `https`, and that is NOT an oversight to be "fixed" into agreement
// with the upload base, which derives `http` for a loopback host (`upload_scheme`, the resolver). The two
// are asymmetric because the platform rules behind them are different, and only one has an exemption:
//   * the UPLOAD BASE is an ordinary network request, governed by ATS — which exempts the loopback IP
//     literal, which is the only reason a simulator can reach `deno task dev:local` over plain HTTP.
//   * LINK_ORIGIN is a UNIVERSAL LINK origin. `applinks:` and the AASA are HTTPS-only by Apple's
//     contract (capability `event-link`: "the HTTPS Universal Link"); iOS will not claim an `http://`
//     link at all, so deriving a scheme here would generate a constant that cannot work.
// A local deployment therefore gets an https LINK_ORIGIN it never exercises — correct and inert — rather
// than an http one that would look consistent and mean nothing.
val generateLinkOrigin by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/linkOrigin/kotlin")
    val domain = linkDomain
    inputs.property("domain", domain)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().asFile.resolve("app/snapsync/model/LinkOrigin.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            // GENERATED by :domain's generateLinkOrigin task — do not edit.
            // Source of truth: the resolved deployment (see deployments/ and scripts/resolve-deployment.py).
            package app.snapsync.model

            /**
             * The event link's canonical origin (capability `event-link`). Both halves of the codec are
             * anchored here: [encodeEventUrl] emits it and [decodeEventUrl] matches it, so producer and
             * consumer cannot drift.
             */
            public const val LINK_ORIGIN: String = "https://$domain"

            """.trimIndent(),
        )
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.configure { kotlin.srcDir(generateLinkOrigin) }
        commonMain.dependencies {
            // The per-zone library allowlist (spec `module-architecture`, "Core purity is closed by
            // default"): coroutines (StateFlow/Flow port shapes), serialization + datetime (the
            // config/manifest vocabulary and cutoff codecs), kermit (the engine's diagnostics).
            api(libs.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        // NO iosMain block, ever: `:domain` has no iosMain source directory (spec
        // `module-architecture`; guard: the zone gates' D6 scope + `compileIosMainKotlinMetadata`).
    }
}

// ---- Coverage bounds (capability `coverage-bounds`) ---------------------------------------------
//
// A FLOOR on this module's coverage, seeded at what the tree measured when the gate landed, and
// permitted to move in one direction only: UP. The destination is full coverage, and these numbers
// are the distance still to travel.
//
// RAISING a bound is ordinary work - do it in the change that makes it true. LOWERING one requires a
// stated forcing proof in that change's description, naming what makes the loss of coverage
// unavoidable. Nothing checks this: it is a ratchet carried by this paragraph and by review, and it
// is deliberately NOT a proof. `complexity-budgets` carries the same contract at the opposite
// polarity - a ceiling that may only fall.
//
// TWO RULES, because they fail on different things. The aggregate catches a broad slide that leaves
// every package above the floor; the PACKAGE FLOOR - "no package here is worse than this" - catches
// one package rotting behind well-tested neighbours, which is the shape an untested class has.
//
// ENGINE: Kover's default, not JaCoCo. The two disagree by up to 26% on a single package's
// denominator, so every number below is engine-specific and switching engines means re-seeding all
// of them in that same change.
//
// Bounds are whole percentages (`minValue` is an `Int`), so each concedes up to 1% of its scope.
//
// The package floor names `flow/` at 57%: `SilentPush` and `Foreground` have unit tests,
// `Provision`, `DownloadBackstop` and `Background` do not - and the last two are executed by no
// test in the repository on any tier. That is the next debt to pay here.
kover {
    reports {
        total {
            verify {
                onCheck = true
                rule(":domain aggregate") {
                    bound {
                        minValue = 91
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                    bound {
                        minValue = 81
                        coverageUnits = CoverageUnit.BRANCH
                    }
                }
                // No per-package BRANCH rule: branch denominators per package run as low as 6 in this
                // tree, where a single uncovered arm moves the number by 17 points.
                rule(":domain package floor") {
                    groupBy = GroupingEntityType.PACKAGE
                    bound {
                        minValue = 57
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                }
            }
        }
    }
}
