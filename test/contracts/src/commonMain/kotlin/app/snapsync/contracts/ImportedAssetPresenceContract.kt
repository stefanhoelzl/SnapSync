package app.snapsync.contracts

import app.snapsync.model.AssetPresence
import app.snapsync.ports.ImportedAssetPresence
import kotlin.test.assertEquals

/** The states an [ImportedAssetPresence]'s library can be found in, as far as a clause cares. */
enum class ImportedAssetPresenceState {
    /** The process holds no photo grant (undetermined or denied). */
    NO_GRANT,

    /** A full grant, and [SEED_COUNT] assets seeded in the clause's window. */
    GRANTED_SEEDED,
}

/**
 * What every [ImportedAssetPresence] promises (capability `port-contracts` — this list IS the specification of
 * the port's obligations).
 *
 * `ABSENT` is an instruction: the download controller clears an unconfirmed import's marker on it, and a second
 * asset is then created. So a library the process may not look at answers `UNKNOWN`, never `ABSENT`. A real
 * binding binds the grant-aware composition production calls, which owns that half of the contract.
 */
object ImportedAssetPresenceContract :
    Contract<ImportedAssetPresenceState, SeededLibrary<ImportedAssetPresence>>("ImportedAssetPresence") {

    override val clauses = clauses {

        clause("NO_GRANT_IS_UNKNOWN_NEVER_ABSENT", ImportedAssetPresenceState.NO_GRANT) { seeded ->
            val id = absentAssetId("NO_GRANT_IS_UNKNOWN_NEVER_ABSENT")
            assertEquals(
                mapOf(id to AssetPresence.UNKNOWN),
                seeded.port.presence(setOf(id)),
                "without a grant the library cannot be looked at; ABSENT would clear a live marker",
            )
        }

        clause("GRANTED_SEEDED_ASSETS_ARE_PRESENT", ImportedAssetPresenceState.GRANTED_SEEDED) { seeded ->
            assertEquals(SEED_COUNT, seeded.ids.size, "the binding seeded what the state promises")
            assertEquals(seeded.ids.associateWith { AssetPresence.PRESENT }, seeded.port.presence(seeded.ids))
        }

        clause("GRANTED_UNKNOWN_ASSET_IS_ABSENT", ImportedAssetPresenceState.GRANTED_SEEDED) { seeded ->
            val id = absentAssetId("GRANTED_UNKNOWN_ASSET_IS_ABSENT")
            assertEquals(mapOf(id to AssetPresence.ABSENT), seeded.port.presence(setOf(id)))
        }

        clause("EVERY_ID_ASKED_ABOUT_HAS_AN_ENTRY", ImportedAssetPresenceState.GRANTED_SEEDED) { seeded ->
            val asked = seeded.ids + absentAssetId("EVERY_ID_ASKED_ABOUT_HAS_AN_ENTRY")
            assertEquals(asked, seeded.port.presence(asked).keys)
        }

        clause("ASKING_ABOUT_NOTHING_ANSWERS_NOTHING", ImportedAssetPresenceState.GRANTED_SEEDED) { seeded ->
            assertEquals(emptyMap(), seeded.port.presence(emptySet()))
        }
    }
}
