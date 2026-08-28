package app.snapsync.architecture

import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Shared scanning for the five zone gates (capability `architecture-guards`, requirement "The zone
 * gates exist before their zones, pending and self-arming"; decision record:
 * `pin-runtime-identity-and-zone-gates`). Law semantics: `module-architecture` "Zones inside the
 * core" / "Commands cross one door".
 *
 * SELF-ARMING (the `FakeHonestyTest` pattern): each gate's zone does not exist yet — it is created
 * by a later migration step (3a: model/ports, 5/6: feature, 7: compose, 8: flow, 9: presentation).
 * Until the zone's directory exists the gate reports itself pending — visibly, never vacuously —
 * and arms on the zone's first file with zero gate edits. A zone directory that exists but scans
 * empty FAILS: a layout drift must surface as red, not as a gate that passes forever.
 *
 * SCOPE ASSUMPTION (design D6, named here so a deviation is a conscious edit): the `:domain` module
 * roots at `domain/` with `src/` beside the legacy submodule directories until they empty — zones
 * live at `domain/<zone>/src/commonMain/kotlin/…/<zone>/` — and `:ui:presentation` at
 * `ui/presentation/src`. If migration step 3a/9 picks a different root, these gates go
 * pending-forever (the PENDING line names the absent scope); that step's diff must then edit these
 * paths, reviewed against D6.
 */
internal object ZoneGates {

    val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /** The core's five zones, each now its own Gradle module. */
    val zoneTokens = listOf("model", "ports", "feature", "flow", "compose")

    /** The core's tree root. Each zone is its own module beneath it: `domain/<zone>/`. */
    val domainSrc = File(repoRoot, "domain")

    /**
     * A zone's production sources — `domain/<zone>/src/commonMain/kotlin/app/snapsync/<zone>`.
     *
     * The zone edges themselves are no longer text-gated: each zone is a Gradle module declaring only
     * the zone dependency its law permits, so a forbidden reference does not resolve (spec
     * `module-architecture`). What remains here serves the two properties the module graph cannot
     * express — feature mutual blindness, and a flow's inability to outlive its run.
     */
    fun zoneFiles(root: File, zone: String): List<File>? {
        val dir = File(root, "$zone/src/commonMain/kotlin/app/snapsync/$zone")
        if (!dir.isDirectory) return null
        return dir.walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    /** Every zone's production sources, for the gates that scan the core as a whole. */
    fun domainMainFiles(): List<File> =
        zoneTokens.flatMap { zoneFiles(domainSrc, it).orEmpty() }

    /**
     * A zone's sources, or a FAILURE — never a pass.
     *
     * This replaced a `pendingOrEmpty` helper that printed "PENDING" and returned green when a zone
     * directory was absent. That was for the migration, which is finished; afterwards it was purely a
     * way for a gate to fail open, because renaming a zone directory disarmed its gate silently.
     */
    fun requireZone(gate: String, zone: String): List<File> {
        val files = zoneFiles(domainSrc, zone)
        assertTrue(
            files != null,
            "$gate gate: no `$zone` zone at domain/$zone/src/commonMain — the core's layout moved; " +
                "re-point the scan rather than letting this gate pass on an absent scope",
        )
        assertTrue(
            files!!.isNotEmpty(),
            "$gate gate: the `$zone` zone exists but the scan matched no sources — layout changed; " +
                "fix the scan or this gate fails open forever",
        )
        return files
    }

    /**
     * Project-internal references of a file: every `app.snapsync.*` dotted path outside line
     * comments, KDoc bodies, string literals, and the file's own `package` line. Matching source
     * text (not the import list) is what catches a fully-qualified sidestep.
     */
    fun projectRefs(file: File): List<Pair<Int, String>> {
        val refPattern = Regex("""app\.snapsync(\.[A-Za-z0-9_]+)+""")
        return file.readText().lineSequence().withIndex()
            .filterNot { (_, line) ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("package ")
            }
            .flatMap { (i, line) ->
                val withoutStrings = line.replace(Regex("\"[^\"]*\""), "\"\"")
                refPattern.findAll(withoutStrings).map { (i + 1) to it.value }
            }
            .toList()
    }

    /** The zone a reference lands in: its first zone-named segment, or null for zone-less (legacy) code. */
    fun zoneOf(ref: String): String? = ref.split('.').firstOrNull { it in zoneTokens }

    /**
     * Kotlin source with comments blanked out, newlines preserved so line numbers survive. Shared by
     * the two gates that must read **code only**: [PlatformIdentifierTest] (whose exemption for prose
     * is its whole design) and [CompositionSeamTest] (whose subject, `AppPorts`, documents the
     * function-typed fields it no longer has — `presentPhotoPicker: () -> Unit` appears verbatim in a
     * KDoc there, and a scanner that read comments would report a field that does not exist).
     *
     * Written by hand rather than by regex because none of the three cases is regular: Kotlin block
     * comments **nest**, a `//` inside a string literal (`"https://…"`) is not a comment, and a raw
     * `"""…"""` string may contain both. Blanking rather than deleting keeps the offset→line mapping
     * honest, so a violation report points at the line a reader can open.
     *
     * String literals are deliberately **kept**: a literal is code. A log line or a JSON key naming a
     * platform API in the core is exactly the leak the identifier gate is for — the exemption is for
     * prose *about* the code, not for values inside it.
     */
    fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var blockDepth = 0
        fun blank(c: Char) = out.append(if (c == '\n') '\n' else ' ')
        while (i < source.length) {
            val c = source[i]
            when {
                blockDepth > 0 -> when {
                    source.startsWith("/*", i) -> { blockDepth++; blank(' '); blank(' '); i += 2 }
                    source.startsWith("*/", i) -> { blockDepth--; blank(' '); blank(' '); i += 2 }
                    else -> { blank(c); i++ }
                }
                source.startsWith("/*", i) -> { blockDepth = 1; blank(' '); blank(' '); i += 2 }
                source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') { blank(' '); i++ }
                source.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\""); i += 3
                    while (i < source.length && !source.startsWith("\"\"\"", i)) { out.append(source[i]); i++ }
                    if (i < source.length) { out.append("\"\"\""); i += 3 }
                }
                c == '"' -> {
                    out.append(c); i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\' && i + 1 < source.length) { out.append(source[i]); i++ }
                        out.append(source[i]); i++
                    }
                    if (i < source.length) { out.append('"'); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** The feature a reference or path names: the segment after the first `feature` segment. */
    fun featureOf(segments: List<String>): String? {
        // LAST occurrence, not the first: since the split, a feature file's path carries `feature`
        // twice — once as the Gradle module (`domain/feature/`) and once as the package
        // (`app/snapsync/feature/<name>/`). Taking the first yielded `src` as the feature name, so
        // every feature looked like a sibling of every other.
        val idx = segments.lastIndexOf("feature")
        return if (idx != -1 && idx + 1 < segments.size) segments[idx + 1] else null
    }

    fun featureOfRef(ref: String): String? = featureOf(ref.split('.'))

    fun featureOfFile(file: File): String? = featureOf(file.toRelativeString(repoRoot).replace('\\', '/').split('/'))

    fun violation(file: File, line: Int, ref: String, law: String): String =
        "${file.toRelativeString(repoRoot)}:$line references $ref — $law"

    fun assertNoViolations(gate: String, violations: List<String>) {
        assertTrue(
            violations.isEmpty(),
            "$gate gate violations (law: module-architecture \"Zones inside the core\"):\n  " +
                violations.joinToString("\n  "),
        )
    }
}
