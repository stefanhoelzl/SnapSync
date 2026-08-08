package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every nullable-returning seam states the consequence that makes its collapse safe**
 * (capability `architecture-guards`; spec `module-architecture`, "Absence is never silent").
 *
 * A seam that can answer "nothing" must distinguish *nothing* from *could not tell* wherever the two
 * have different consequences. Where they are deliberately collapsed, the collapse must name the
 * consequence that makes it safe **for every cause it absorbs** — not only for the cause its author
 * had in mind. This guard cannot judge that; no text check can. What it enforces is the one thing a
 * text check *can*: that the question was **asked and answered in writing**.
 *
 * That is the whole defect it targets. The bug this rule came from was not a wrong reason — it was
 * **no reason**: `eventLinkFromUserActivity` answered a three-state question with `String?` because
 * nobody ever weighed the collapse, so a discarded event link and a link iOS never delivered were
 * byte-identical in every device log (Bugsink `SNAPSYNC-3`). The sibling instance had a reason and it
 * was incomplete: `UploadExtensionRoot.attestToken` justified swallowing `errSecInteractionNotAllowed`
 * (locked device, retryable 401) while also swallowing `errSecMissingEntitlement` — permanent, not
 * retryable, and the cause of the 2026-07-21 "dead in the water" incident.
 *
 * **The population is derived; only the verdicts are authored.** A new nullable seam in a scanned zone
 * fails the build until someone writes down what a null costs there. The guard demands a reason; it
 * does not maintain a list — a hand-maintained inventory would rot exactly the way the entry-point
 * enumeration this change replaced did.
 *
 * **Scope, and why it is not the whole tree.** `ports/`, `model/`, and the two composition roots are
 * small and bounded, which is what makes derivation cheap and non-vacuous here. It is also where the
 * evidence pointed: the first draft of this guard covered `ports/` alone, and the audit then showed
 * that *both* motivating violations fall outside it — one in `model/`, one in a root. A guard that
 * catches neither of the bugs that prompted it is worth widening before it ships, not after.
 * `feature/` and adapter internals stay out; they surface when someone next touches them.
 */
class AbsenceIsNamedTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /** The bounded zones this rule is enforced in. See the class doc for why not the whole tree. */
    private val scannedZones = listOf(
        "domain/src/commonMain/kotlin/app/snapsync/ports",
        "domain/src/commonMain/kotlin/app/snapsync/model",
        "app/ios/src/iosMain",
        "app/ios/extension/src/iosMain",
    )

    /** The marker a verdict is written with — greppable on purpose, so a reader can list them all. */
    private val verdictMarker = "Absence:"

    /** How far above a declaration its KDoc may start. Generous: these docs carry real argument. */
    private val kdocLookBehindLines = 40

    private data class Seam(val file: String, val line: Int, val declaration: String)

    /**
     * A nullable-returning function declaration. Matched on the signature's closing `)` followed by a
     * nullable return type, which also catches the multi-line signatures (`mintToken`) a per-line
     * regex silently misses — and a silent miss is the failure mode this whole change exists to end.
     */
    private val nullableFun = Regex(
        """^\s*(?:internal |private |public )?(?:suspend )?fun\s+[a-zA-Z][\w.<>]*\s*\(""",
    )

    private fun seams(): List<Seam> = scannedZones.flatMap { zone ->
        val dir = File(repoRoot, zone)
        assertTrue(dir.isDirectory, "guard is scanning nothing — $zone not found from $repoRoot")
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val lines = file.readText().lines()
            lines.mapIndexedNotNull { index, line ->
                if (!nullableFun.containsMatchIn(line)) return@mapIndexedNotNull null
                // Find the parameter list's OWN closing paren by balancing, then read only the rest
                // of THAT line. Taking a fixed window instead runs into the next declaration and
                // reports its return type as this one's — which it did, on the first run.
                val chunk = lines.drop(index).take(12).joinToString("\n")
                val afterParams = returnTypeOf(chunk) ?: return@mapIndexedNotNull null
                val returnsNullable = Regex("""^\s*:\s*[A-Za-z][\w<>, .]*\?\s*($|=|\{)""")
                    .containsMatchIn(afterParams)
                if (!returnsNullable) null
                else Seam(file.toRelativeString(repoRoot), index + 1, line.trim())
            }
        }.toList()
    }


    /** The text following a declaration's own closing paren, on that same line — `null` if unbalanced. */
    private fun returnTypeOf(chunk: String): String? {
        val open = chunk.indexOf('(').takeIf { it >= 0 } ?: return null
        var depth = 0
        for (i in open until chunk.length) {
            if (chunk[i] == '(') depth++
            if (chunk[i] == ')') {
                depth--
                if (depth == 0) return chunk.substring(i + 1).substringBefore('\n')
            }
        }
        return null
    }

    private fun hasVerdict(seam: Seam): Boolean {
        val lines = File(repoRoot, seam.file).readText().lines()
        val from = (seam.line - 1 - kdocLookBehindLines).coerceAtLeast(0)
        return lines.subList(from, seam.line - 1).joinToString("\n").contains(verdictMarker)
    }

    @Test
    fun `the guard actually derived a population`() {
        // Non-vacuity twin: a changed declaration shape must fail here, never pass by matching
        // nothing. The floor is well below the real count so ordinary churn does not trip it.
        val found = seams().size
        assertTrue(found >= 15, "derived only $found nullable seams — the derivation is broken, not the code")
    }

    @Test
    fun `every nullable seam names what a null costs`() {
        val unexplained = seams().filterNot(::hasVerdict)
        if (unexplained.isEmpty()) return
        fail(
            buildString {
                appendLine("These seams can answer \"nothing\" without saying what that costs:")
                unexplained.forEach { appendLine("  ${it.file}:${it.line}  ${it.declaration}") }
                appendLine()
                appendLine("Add a `$verdictMarker …` line to the declaration's KDoc naming the consequence that")
                appendLine("makes the collapse safe FOR EVERY CAUSE IT ABSORBS — or return a type that keeps")
                appendLine("\"nothing\" and \"could not tell\" apart (ConfigRead, SecureStoreRead, JoinLoad all do).")
                appendLine()
                appendLine("This is not paperwork. `eventLinkFromUserActivity` returned String? because the")
                appendLine("question was never asked, and a discarded event link then looked exactly like one")
                appendLine("iOS never delivered — which is why SNAPSYNC-3 could not be diagnosed from a dump.")
                appendLine("A reason that is present but wrong is a review problem; this guard only ensures")
                appendLine("there is one to review.")
            },
        )
    }
}
