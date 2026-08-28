package app.snapsync.architecture

import kotlin.test.Test

/**
 * **Features are mutually blind** (capability `architecture-guards`; law: `module-architecture`
 * "Zones inside the core" / "Rules in features, order in flows"). A feature references only
 * `model/`, `ports/`, and itself — never a sibling feature (features coordinate via one-writer
 * durable state behind shared ports, not via each other), never `flow/` or `compose/`, never
 * legacy code. Features are enumerated from the directory listing, so a new feature is born in
 * scope with zero gate edits. Features live at `domain/feature/src/commonMain/…/feature/<name>/`; a
 * missing scope FAILS rather than reporting itself pending (see [ZoneGates]).
 */
class ZoneFeatureBlindnessTest {

    @Test
    fun `no feature references a sibling feature`() {
        val files = ZoneGates.requireZone("feature-blindness", "feature")
        val violations = files.flatMap { file ->
            val own = ZoneGates.featureOfFile(file)
            ZoneGates.projectRefs(file).mapNotNull { (line, ref) ->
                when (ZoneGates.zoneOf(ref)) {
                    "model", "ports" -> null
                    "feature" ->
                        if (ZoneGates.featureOfRef(ref) == own) null
                        else ZoneGates.violation(
                            file, line, ref,
                            "feature/$own must not reference sibling feature/${ZoneGates.featureOfRef(ref)}",
                        )
                    else -> ZoneGates.violation(file, line, ref, "features reference only model/ and ports/")
                }
            }
        }
        ZoneGates.assertNoViolations("feature-blindness", violations)
    }
}
