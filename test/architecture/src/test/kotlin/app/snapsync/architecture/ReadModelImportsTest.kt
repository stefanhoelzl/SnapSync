package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Outside `feature/`, a consumer references only read-models** (`docs/architecture.md`, "The zone
 * gates"; law: `docs/architecture.md`, "Zones inside the core").
 *
 * A read-model is whatever a feature declares in its `feature/<feature>/readmodel/` package — the package IS the
 * definition, so the rule is mechanical. The presentation zone, the host, every `:ui:*` module and the control
 * channel's client depend on the whole `:domain:feature` module, so a module edge cannot draw this line: it runs
 * INSIDE one module, which is exactly the case the zone gates reserve for a derived text gate.
 *
 * This gate replaces the "presentation-imports gate" the spec once described as compile-enforced. No test ever
 * implemented that one, and presentation's former `api(:domain:feature)` edge made every feature type resolve.
 *
 * Main AND test sources are scanned: a test that names a feature command is a consumer reaching past the
 * read-model like any other. References are read from code text (comments and strings excluded, see
 * [ZoneGates.projectRefs]), fully-qualified names included.
 */
class ReadModelImportsTest {

    /** The scopes held to read-models. Each must exist and hold sources, or the gate fails rather than pass. */
    private val scopes = listOf(
        "domain/presentation/src",
        "domain/host/src",
        "ui/screens/src",
        "ui/components/src",
        "test/control/src",
    )

    /**
     * Named, not silently absent: `:app:desktop`'s harness still wires feature behaviour directly (`JoinEvent`,
     * `toJoinLoad`, `StoreDownloadStatusSource`). It is exempt until the entry-surface phase (11g) rewires it onto
     * the protocol. [the desktop exemption is still needed] fails the day it is not, so the exemption cannot
     * outlive its reason.
     */
    private val exempt = "app/desktop/src"

    private fun sources(roots: List<String> = scopes): List<File> = roots.flatMap { root ->
        val dir = File(ZoneGates.repoRoot, root)
        assertTrue(dir.isDirectory, "read-model gate: scope $root is gone — re-point the scan, never let it pass empty")
        val files = dir.walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue(files.isNotEmpty(), "read-model gate: scope $root holds no Kotlin sources — the layout moved")
        files
    }

    private fun featureRefs(roots: List<String> = scopes): List<Triple<File, Int, String>> =
        sources(roots).flatMap { file ->
            ZoneGates.projectRefs(file)
                .filter { (_, ref) -> ref.split('.').getOrNull(2) == "feature" }
                .map { (line, ref) -> Triple(file, line, ref) }
        }

    @Test
    fun `consumers outside feature reference only readmodel packages`() {
        val violations = featureRefs()
            .filterNot { (_, _, ref) -> "readmodel" in ref.split('.') }
            .map { (file, line, ref) ->
                ZoneGates.violation(
                    file, line, ref,
                    "outside feature/, only a feature's readmodel package may be named — move the type there if " +
                        "it is a read-model, or reach it through the command bundle if it is a command",
                )
            }
        ZoneGates.assertNoViolations("read-model-imports", violations)
    }

    @Test
    fun `the read-model gate sees read-model references (non-vacuity)`() {
        val seen = featureRefs().count { (_, _, ref) -> "readmodel" in ref.split('.') }
        assertTrue(
            seen > 0,
            "read-model gate: no `feature.*.readmodel.*` reference found in any scope — the package was renamed or " +
                "the scan moved, and the gate above now passes on nothing",
        )
    }

    @Test
    fun `the desktop exemption is still needed`() {
        val beyond = featureRefs(listOf(exempt)).count { (_, _, ref) -> "readmodel" !in ref.split('.') }
        assertTrue(
            beyond > 0,
            "read-model gate: $exempt names no feature type outside a readmodel package any more — delete the " +
                "exemption and add it to the scopes, so the harness is held to the rule it now keeps",
        )
    }
}
