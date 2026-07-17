package app.snapsync.architecture

import kotlin.test.Test

/**
 * **flow/ never reaches a port** (capability `architecture-guards`; law: `module-architecture`
 * "Zones inside the core": flows coordinate, never decide). A flow references only `model/` and
 * `feature/` — a flow touching `ports/` is a flow doing I/O, which is a feature's job; `compose/`
 * and legacy references are equally out. Pending until migration step 8 creates
 * `domain/src/…/flow/`; see [ZoneGates] for the arming contract and the D6 scope assumption.
 */
class ZoneFlowTest {

    @Test
    fun `flows reference only model and feature`() {
        val files = ZoneGates.zoneFiles(ZoneGates.domainSrc, "flow")
        if (ZoneGates.pendingOrEmpty("flow", ZoneGates.domainSrc, files)) return
        val violations = files!!.flatMap { file ->
            ZoneGates.projectRefs(file).mapNotNull { (line, ref) ->
                when (ZoneGates.zoneOf(ref)) {
                    "model", "feature", "flow" -> null
                    "ports" -> ZoneGates.violation(file, line, ref, "flow/ never references ports/ — I/O is a feature's job")
                    else -> ZoneGates.violation(file, line, ref, "flow/ references only model/ and feature/")
                }
            }
        }
        ZoneGates.assertNoViolations("flow", violations)
    }
}
