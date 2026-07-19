package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Fakes are honest doubles — rigging lives in the world, physically** (capability
 * `architecture-guards`; law: `module-architecture` "The module set withholds"; decision record:
 * `establish-target-architecture`).
 *
 * The target splits test doubles in two: `:adapter:generic:fake` holds HONEST in-memory implementations of
 * port contracts (what the composition smoke test and every integration test stand on), and
 * `:test:world` holds the operator levers that rig them (failure injection, job completion,
 * backend coupling). "Honest" enforced as an adjective rots — the review named the exact path: an
 * agent needs a failure lever, the fake is right there in commonMain, `var failNextSave` appears,
 * and ten edits later the fakes ARE the old world, except everything stands on them. So honesty is
 * a mechanical property here: a fake's public surface is its port contract plus a constructor.
 *
 * SELF-ARMING: `:adapter:generic:fake` did not exist when this gate was authored (migration step 10
 * created it, as `:adapter:fake`). Until the
 * directory exists this gate reports itself pending — visibly, not vacuously — and arms on the
 * module's first file with zero gate edits (fail-closed on novelty).
 */
class FakeHonestyTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private val fakeRoot = File(repoRoot, "adapter/generic/fake")

    private fun fakeSources(): List<File> = fakeRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path && "Main/" in it.path.replace('\\', '/') }
        .toList()

    @Test
    fun `fakes expose only port members and a constructor`() {
        if (!fakeRoot.isDirectory) {
            println("fake-honesty gate: PENDING — adapter/generic/fake does not exist yet; arms on its first file")
            return
        }
        val sources = fakeSources()
        assertTrue(
            sources.isNotEmpty(),
            "adapter/generic/fake exists but the gate scanned no sources — layout changed; fix the scan or " +
                "this gate fails open forever",
        )
        val violations = sources.flatMap { file ->
            val code = file.readText().lineSequence()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .toList()
            buildList {
                code.forEachIndexed { i, line ->
                    val t = line.trimStart()
                    // Public mutable state = a lever. (Kotlin default visibility is public.)
                    if (Regex("""^(public\s+)?var\s""").containsMatchIn(t)) {
                        add("${file.toRelativeString(repoRoot)}:${i + 1} public var — a lever; move it to a :test:world wrapper")
                    }
                    // Public non-override function = surface beyond the port contract.
                    if (Regex("""^(public\s+)?(suspend\s+)?fun\s""").containsMatchIn(t) && "override" !in t) {
                        add("${file.toRelativeString(repoRoot)}:${i + 1} public non-port function — the port contract is the whole surface")
                    }
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "rigging in :adapter:generic:fake — honest doubles expose port members + a constructor taking " +
                "initial state, nothing else:\n  ${violations.joinToString("\n  ")}",
        )
    }
}
