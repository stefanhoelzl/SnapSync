package app.snapsync.architecture.migration

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The migration burn-down (capability `architecture-guards`; decision record
 * `establish-target-architecture` D8): one measurement per law, each a DERIVED scan of the tree —
 * no measurement enumerates files by name except the deletion ledger, whose entries are the point.
 *
 * RED UNTIL DONE (decision D8, revised 2026-07-17): the test writes `build/burn-down/report.md`
 * (CI appends it to the job summary), prints it, and then FAILS while total distance is nonzero —
 * the `verify` job is red by design for the whole migration and green exactly at completion. It is
 * detached from `check` and non-required — and ios-release Guard 4 and /ship's watcher judge
 * required checks only (derived from branch protection), so its red blocks nothing. A measurement that cannot scan what it claims renders MEASUREMENT BROKEN and also fails.
 *
 * DONE per measurement = count 0. At zero, the law moves into `:test:architecture` as a permanent
 * gate and its row is deleted here; the module dies when the table is empty — green, then gone.
 */
class BurnDownTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private fun sources(vararg roots: String): List<File> = roots.flatMap { root ->
        File(repoRoot, root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path && "/src/" in it.path }
            .toList()
    }

    private data class Row(val law: String, val count: Int, val detail: String)

    /** The end-state module list (design D1). The beacon itself is excluded — it dies at completion. */
    private val targetModules = setOf(
        ":domain",
        ":ui:presentation", ":ui:screens", ":ui:components",
        ":adapter:ios:ext-safe", ":adapter:ios:app-only", ":adapter:generic", ":adapter:fake",
        ":app:ios", ":app:ios:extension", ":app:desktop",
        ":test:world", ":test:integration", ":test:architecture", ":test:harness-driver",
        ":tools:diagrams",
    )

    @Test
    fun `measure and report`() {
        val rows = mutableListOf<Row>()

        // ── Law: The module set withholds; packages organize ─────────────────────────────────────
        rows += runCatching {
            val includes = Regex("""include\("([^"]+)"\)""")
                .findAll(File(repoRoot, "settings.gradle.kts").readText())
                .map { it.groupValues[1] }.toSet() - ":test:architecture:migration"
            val toRemove = includes - targetModules
            val toCreate = targetModules - includes
            Row(
                "module set matches the target",
                toRemove.size + toCreate.size,
                "remove ${toRemove.size} (${toRemove.sorted().joinToString(" ")}), create ${toCreate.size}",
            )
        }.getOrElse { broken("module set", it) }

        // ── Law: Zones inside the core (today: capability↔capability + domain→capability edges) ──
        rows += runCatching {
            val edges = File(repoRoot, "capability").walkTopDown().plus(File(repoRoot, "domain").walkTopDown())
                .filter { it.name == "build.gradle.kts" }
                .flatMap { build ->
                    val from = build.parentFile.toRelativeString(repoRoot).replace('/', ':')
                    Regex("""project\(":(capability|domain):([a-z-]+)"""").findAll(build.readText())
                        .map { ":$from → :${it.groupValues[1]}:${it.groupValues[2]}" }
                }
                .filter { edge -> // the law forbids capability→capability and domain→capability
                    val (from, to) = edge.split(" → ")
                    (from.startsWith(":capability") && to.startsWith(":capability")) ||
                        (from.startsWith(":domain") && to.startsWith(":capability"))
                }.toList()
            Row("zones inside the core (illegal graph edges)", edges.size, edges.sorted().joinToString("; "))
        }.getOrElse { broken("zone edges", it) }

        // ── Law: Shells are wiring only (decision count in :app:* Kotlin + Swift shells) ──────────
        rows += runCatching {
            // Prefer detekt's syntax-tree count (CyclomaticComplexMethod over the shells — run
            // `./gradlew detektAppShell` first); the regex heuristic is the cold-start fallback.
            // A text rule alone would drown: the shells are ~63% comment by detekt's own measure.
            val detektXml = File(repoRoot, "build/reports/detekt/detekt.xml")
            val viaDetekt = detektXml.isFile
            val kotlinDecisions = if (viaDetekt) {
                Regex("""<error\s""").findAll(detektXml.readText()).count()
            } else sources("app").sumOf { f ->
                val body = f.readText().lineSequence()
                    .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                    .joinToString("\n")
                Regex("""(?<![.\w])(if|when)\s*[({]""").findAll(body).count()
            }
            val kotlinSource = if (viaDetekt) "detekt" else "regex heuristic — run detektAppShell for the real count"
            val swiftDecisions = File(repoRoot, "iosApp").walkTopDown()
                .filter { it.isFile && it.extension == "swift" }
                .sumOf { f ->
                    val body = f.readText().lineSequence()
                        .filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
                    Regex("""(?<!\w)(if|guard|switch)\s""").findAll(body).count()
                }
            Row(
                "shells are wiring only (decisions in shells)",
                kotlinDecisions + swiftDecisions,
                "$kotlinDecisions in app/**.kt (via $kotlinSource), $swiftDecisions in iosApp/**.swift",
            )
        }.getOrElse { broken("shell decisions", it) }

        // ── Law: Ports are the I/O boundary (files mixing a port interface with a technology impl) ─
        rows += runCatching {
            val mixed = sources("adapter", "domain", "capability").filter { f ->
                val t = f.readText()
                Regex("""^\s*interface\s""", RegexOption.MULTILINE).containsMatchIn(t) &&
                    (t.contains("import io.ktor") || t.contains("import app.cash.sqldelight"))
            }.map { it.toRelativeString(repoRoot) }
            Row("port interfaces mixed with technology impls (files to split)", mixed.size, mixed.sorted().joinToString("; "))
        }.getOrElse { broken("mixed files", it) }

        // ── The deletion ledger (proposal; each present item = 1) ─────────────────────────────────
        rows += runCatching {
            val toml = File(repoRoot, "gradle/libs.versions.toml").readText()
            // The beacon's own source quotes every pattern below, so it must not scan itself — the
            // same self-exclusion targetModules already applies.
            // `adapter` joined the scanned roots at migration step 4 (the Enrollment copies must keep
            // counting after HttpEnrollment moved into :adapter:generic — a loud-stale list, updated
            // in-PR per the plan's rule).
            val allSrc = sources("adapter", "domain", "capability", "app", "test")
                .filterNot { "test/architecture/migration/" in it.path.replace('\\', '/') }
            fun declared(pattern: String) = allSrc.count { Regex(pattern).containsMatchIn(it.readText()) }
            val items = buildList {
                if (File(repoRoot, "capability/config/src/jvmMain").exists()) add("QrGeneratorMain (config jvmMain)")
                if ("zxing" in toml) add("zxing catalog entries")
                if ("kotlincrypto" in toml) add("kotlincrypto catalog entries")
                if (File(repoRoot, "capability/device-id").exists()) add(":capability:device-id")
                if (declared("""interface LedgerReader""") > 0) add("LedgerReader")
                if (declared("""class LoggingPushReceiver""") > 0) add("LoggingPushReceiver")
                if (declared("""interface EventMetadataSource""") > 0) add("EventMetadataSource (duplicate GET /events client)")
                if (declared("""interface LeaveNotifier""") > 0) add("LeaveNotifier interface ceremony")
                if (declared("""enum class ArrowLevel""") > 0 && declared("""enum class Arrow\b""") > 0) {
                    add("Arrow/ArrowLevel duplicate enum")
                }
                val uploaders = declared("""class \w*Enrollment""")
                if (uploaders > 1) add("Enrollment ×$uploaders (keep 1)")
            }
            Row("deletion ledger (dead weight still present)", items.size, items.joinToString("; "))
        }.getOrElse { broken("deletion ledger", it) }

        // ── Posture self-check: the beacon must stay detached from `check` ────────────────────────
        rows += runCatching {
            val own = File(repoRoot, "test/architecture/migration/build.gradle.kts").readText()
            Row(
                "beacon stays detached from check (self-check)",
                if ("setDependsOn(emptyList" in own) 0 else 1,
                "a red-until-done test attached to check would freeze every merge",
            )
        }.getOrElse { broken("posture self-check", it) }

        report(rows)

        // RED UNTIL DONE — after the report is written, so a failing run still carries its numbers.
        val distance = rows.sumOf { if (it.count > 0) it.count else 0 }
        val brokenRows = rows.filter { it.count < 0 }
        if (brokenRows.isNotEmpty()) {
            fail("MEASUREMENT BROKEN: ${brokenRows.joinToString { it.law }} — see build/burn-down/report.md")
        }
        if (distance > 0) {
            fail(
                "migration distance $distance — the beacon is RED BY DESIGN until this reaches zero " +
                    "(decision D8 revised; see build/burn-down/report.md for the per-law table). " +
                    "This check is non-required and blocks nothing.",
            )
        }
    }

    private fun broken(name: String, cause: Throwable) =
        Row("MEASUREMENT BROKEN: $name", -1, cause.message ?: cause::class.simpleName.orEmpty())

    private fun report(rows: List<Row>) {
        val md = buildString {
            appendLine("## Architecture migration burn-down")
            appendLine()
            appendLine("| law | distance | detail |")
            appendLine("|---|---:|---|")
            rows.forEach { appendLine("| ${it.law} | ${if (it.count < 0) "⚠" else it.count} | ${it.detail.take(600)} |") }
            appendLine()
            appendLine("_Total: ${rows.filter { it.count > 0 }.sumOf { it.count }} · the `verify` check is RED until this reaches zero (D8 revised) — non-required, it blocks nothing._")
        }
        val out = File(repoRoot, "test/architecture/migration/build/burn-down/report.md")
        out.parentFile.mkdirs()
        out.writeText(md)
        println(md)
    }
}
