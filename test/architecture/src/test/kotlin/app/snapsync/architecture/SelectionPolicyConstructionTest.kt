package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **A selection policy is built by its one derivation, nowhere else** (capability `architecture-guards`;
 * law: `photo-selection-policy`).
 *
 * `SelectionPolicy` is a conjunction of rules that asserts nothing about its contents — the capture floor
 * included. That is deliberate: the invariant *a contributing membership always carries a capture floor*
 * lives at the one place a membership becomes a policy (`selectionRulesFor`, which always emits the floor
 * because the persisted `minPhotoDate` is non-null), not in the type.
 *
 * The cost of moving it there is that `SelectionPolicy(listOf(ExcludeScreenshots))` compiles. A floorless
 * policy is an **unbounded scope**: on iOS its fetch predicate carries no lower bound, so the walk returns
 * the whole library — minutes of synchronous PhotoKit XPC before the watchdog kills the process — and
 * anything it did return would be admitted regardless of when it was taken. That is the failure this
 * capability exists to prevent, and it is why the lower bound is *required* rather than defaulted: this
 * app began as a personal one-way photo backup, where "back up everything of mine" was the right default.
 * In an event the same default uploads a guest's entire camera roll to a stranger's event.
 *
 * So the guarantee the type gave up is held here instead, mechanically, as a red build.
 *
 * **Why not a private constructor**, which would be a type-level guarantee and therefore stronger: the
 * platform translator's tests and both harnesses legitimately need to present **arbitrary** rule lists,
 * including ones the derivation would never emit — that is precisely what a translator test is for. A
 * private constructor forbids those, and the doors that would have to be opened for them would be
 * indistinguishable from the door this guard closes.
 *
 * **Textual and blunt on purpose**, like the other zone gates, and scoped to **production** source sets
 * only: test and harness construction is legitimate and stays free.
 */
class SelectionPolicyConstructionTest {

    /** The one file allowed to construct a policy — where the derivation lives. */
    private val derivationSite = "SelectionPolicy.kt"

    /** `SelectionPolicy(` as a constructor call. Type positions (`: SelectionPolicy`) do not match. */
    private val construction = Regex("""\bSelectionPolicy\(""")

    private fun productionKotlin(): List<File> =
        ZoneGates.repoRoot.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val segments = file.path.replace('\\', '/').split('/')
                val srcIdx = segments.lastIndexOf("src")
                if (srcIdx == -1 || srcIdx + 1 >= segments.size) return@filter false
                val sourceSet = segments[srcIdx + 1]
                sourceSet == "main" || sourceSet.endsWith("Main")
            }
            .toList()

    @Test
    fun `no production code constructs a selection policy outside the one derivation`() {
        val files = productionKotlin()
        assertTrue(files.isNotEmpty(), "no production Kotlin found — has the source layout moved?")

        val violations = files
            .filter { it.name != derivationSite }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (i, line) ->
                    val code = line.substringBefore("//")
                    if (isComment(code)) return@mapNotNull null
                    if (!construction.containsMatchIn(code)) return@mapNotNull null
                    "${file.name}:${i + 1} constructs a SelectionPolicy outside `$derivationSite`. A " +
                        "policy is built by `selectionRulesFor`, which is what guarantees a contributing " +
                        "membership carries its capture floor — a hand-assembled rule list can omit it, " +
                        "and a floorless policy is an unbounded library walk (capability " +
                        "`photo-selection-policy`)."
                }
            }
        ZoneGates.assertNoViolations("selection-policy-construction", violations)
    }

    /** A KDoc / block-comment continuation line. Crude, and sufficient for this codebase's style. */
    private fun isComment(code: String): Boolean =
        code.trimStart().let { it.startsWith("*") || it.startsWith("/*") }

    /** The guard is worth nothing if the derivation is not actually where policies are built. */
    @Test
    fun `the derivation does construct a policy`() {
        val file = ZoneGates.repoRoot
            .walkTopDown()
            .onEnter { it.name != "build" }
            .firstOrNull { it.name == derivationSite && it.path.contains("commonMain") }
        assertTrue(file != null, "$derivationSite not found — has the derivation moved?")
        assertTrue(
            construction.containsMatchIn(file.readText()),
            "$derivationSite no longer constructs a SelectionPolicy — the guard above would then pass " +
                "vacuously while nothing builds a policy at all",
        )
    }
}
