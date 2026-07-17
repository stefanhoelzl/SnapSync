package app.snapsync.architecture

import java.io.File
import kotlin.test.Test

/**
 * **Presentation never references ports or flow** (capability `architecture-guards`; law:
 * `module-architecture` "Commands cross one door"). Presentation observes feature read-models and
 * receives the command bundle by constructor; reaching into `ports/` or `flow/` is exactly what
 * the injection exists to prevent. This is the IMPORT-LEVEL APPROXIMATION of the law (design D7):
 * the finer no-feature-command-invocation rule needs call-site knowledge a text gate does not
 * have and remains a review concern until it has a mechanical form. Pending until migration
 * step 9 creates `ui/presentation/`; see [ZoneGates] for the arming contract and the D6 scope
 * assumption.
 */
class ZonePresentationImportsTest {

    private val presentationSrc = File(ZoneGates.repoRoot, "ui/presentation/src")

    @Test
    fun `presentation references neither ports nor flow`() {
        val files = if (presentationSrc.isDirectory) {
            presentationSrc.walkTopDown()
                .onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        } else {
            null
        }
        if (ZoneGates.pendingOrEmpty("presentation-imports", presentationSrc, files)) return
        val violations = files!!.flatMap { file ->
            ZoneGates.projectRefs(file)
                .filter { (_, ref) -> ZoneGates.zoneOf(ref) in setOf("ports", "flow") }
                .map { (line, ref) ->
                    ZoneGates.violation(
                        file, line, ref,
                        "presentation gets commands injected and observes read-models; it never names ports/ or flow/",
                    )
                }
        }
        ZoneGates.assertNoViolations("presentation-imports", violations)
    }
}
