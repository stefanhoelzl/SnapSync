package app.snapsync.architecture

import kotlin.test.Test

/**
 * **ports references only model** (capability `architecture-guards`; law: `module-architecture`
 * "Zones inside the core"). A port is the need-named I/O boundary; it speaks the domain's
 * vocabulary and nothing else — no feature, no flow, no sibling port module, no legacy code.
 * Pending until migration step 3a creates `domain/src/…/ports/`; see [ZoneGates] for the arming
 * contract and the D6 scope assumption.
 */
class ZonePortsTest {

    @Test
    fun `ports reference only model`() {
        val files = ZoneGates.zoneFiles(ZoneGates.domainSrc, "ports")
        if (ZoneGates.pendingOrEmpty("ports", ZoneGates.domainSrc, files)) return
        val violations = files!!.flatMap { file ->
            ZoneGates.projectRefs(file)
                .filter { (_, ref) -> ZoneGates.zoneOf(ref) !in setOf("model", "ports") }
                .map { (line, ref) ->
                    ZoneGates.violation(file, line, ref, "ports/ imports model/ only")
                }
        }
        ZoneGates.assertNoViolations("ports", violations)
    }
}
