package app.snapsync.architecture

import kotlin.test.Test

/**
 * **Features are mutually blind** (`docs/architecture.md`; law: `docs/architecture.md`
 * "Zones inside the core" / "Rules in features, order in entry points"). A feature references only
 * `model/`, `domain/` (the domain layer's values, MIGRATING until phase 5), `ports/`, `services/` (the shared
 * capabilities it stands on), `scheduling/` (the runtime its outliving work and its passes run on) and itself — never a sibling feature (features coordinate via one-writer durable state
 * behind shared ports, not via each other), never `entrypoints/` or `compose/`, never legacy code. Features are enumerated from the directory listing, so a new feature is born in
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
                    "model", "domain", "ports", "services", "scheduling" -> null
                    "feature" ->
                        if (ZoneGates.featureOfRef(ref) == own) {
                            null
                        } else {
                            ZoneGates.violation(
                                file,
                                line,
                                ref,
                                "feature/$own must not reference sibling feature/${ZoneGates.featureOfRef(ref)}",
                            )
                        }
                    else -> ZoneGates.violation(
                        file,
                        line,
                        ref,
                        "features reference only model/, domain/, ports/, services/ and scheduling/",
                    )
                }
            }
        }
        ZoneGates.assertNoViolations("feature-blindness", violations)
    }
}
