package app.snapsync.architecture

import kotlin.test.Test

/**
 * **model references nothing project-internal outside model** (capability `architecture-guards`;
 * law: `module-architecture` "Zones inside the core"). The vocabulary, domain services, and pure
 * codecs depend on no port, feature, flow, composition, or legacy module — by source text, so a
 * fully-qualified sidestep fails exactly like an import. Pending until migration step 3a creates
 * `domain/src/…/model/`; see [ZoneGates] for the arming contract and the D6 scope assumption.
 */
class ZoneModelPurityTest {

    @Test
    fun `model references only model`() {
        val files = ZoneGates.zoneFiles(ZoneGates.domainSrc, "model")
        if (ZoneGates.pendingOrEmpty("model-purity", ZoneGates.domainSrc, files)) return
        val violations = files!!.flatMap { file ->
            ZoneGates.projectRefs(file)
                .filter { (_, ref) -> ZoneGates.zoneOf(ref) != "model" }
                .map { (line, ref) ->
                    ZoneGates.violation(file, line, ref, "model/ imports nothing project-internal outside model/")
                }
        }
        ZoneGates.assertNoViolations("model-purity", violations)
    }
}
