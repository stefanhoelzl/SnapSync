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
 * live at `domain/src/<sourceSet>/kotlin/…/<zone>/` — and `:ui:presentation` at
 * `ui/presentation/src`. If migration step 3a/9 picks a different root, these gates go
 * pending-forever (the PENDING line names the absent scope); that step's diff must then edit these
 * paths, reviewed against D6.
 */
internal object ZoneGates {

    val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    val domainSrc = File(repoRoot, "domain/src")

    private val zoneTokens = setOf("model", "ports", "feature", "flow", "compose")

    private fun File.isInMainSourceSet(): Boolean {
        val segments = path.replace('\\', '/').split('/')
        val srcIdx = segments.lastIndexOf("src")
        if (srcIdx == -1 || srcIdx + 1 >= segments.size) return false
        val sourceSet = segments[srcIdx + 1]
        return sourceSet == "main" || sourceSet.endsWith("Main")
    }

    /**
     * The `.kt` files of a zone under [root], or `null` when the zone's directory does not exist
     * yet (the pending state). A file's zone is the FIRST zone-named path segment, so a
     * sub-package inside a feature that reuses a zone name does not double-count.
     */
    fun zoneFiles(root: File, zone: String): List<File>? {
        if (!root.isDirectory) return null
        val zoneDirs = root.walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isDirectory && it.name == zone && firstZoneSegment(it) == zone }
            .toList()
        if (zoneDirs.isEmpty()) return null
        return zoneDirs.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.isInMainSourceSet() }
        }
    }

    private fun firstZoneSegment(dir: File): String? =
        dir.toRelativeString(repoRoot).replace('\\', '/').split('/').firstOrNull { it in zoneTokens }

    /** PENDING print + true when the zone does not exist yet; non-vacuity failure on an empty scan. */
    fun pendingOrEmpty(gate: String, root: File, files: List<File>?): Boolean {
        if (files == null) {
            println("$gate gate: PENDING — no ${root.toRelativeString(repoRoot)} zone yet; arms on its first file")
            return true
        }
        assertTrue(
            files.isNotEmpty(),
            "$gate gate: the zone directory exists but the scan matched no sources — layout changed; " +
                "fix the scan or this gate fails open forever",
        )
        return false
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

    /** The feature a reference or path names: the segment after the first `feature` segment. */
    fun featureOf(segments: List<String>): String? {
        val idx = segments.indexOf("feature")
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
