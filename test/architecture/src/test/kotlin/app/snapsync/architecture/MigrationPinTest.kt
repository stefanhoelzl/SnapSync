package app.snapsync.architecture

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **A shipped migration is never edited** (capability `photo-sharing`; `domain/data/build.gradle.kts`,
 * `domain/services/build.gradle.kts`).
 *
 * Each database's schema is its migration chain and nothing else (`deriveSchemaFromMigrations`). A phone that already
 * ran a migration keeps what that migration did; a fresh install runs whatever the file says now. Editing a migration
 * after it shipped therefore splits the two silently — no compile, no SQLDelight task and no test of the current
 * schema can see it, because each of them reads only the current files. This pins every `.sqm` by its digest, so an
 * edit fails here and shows in the PR as a changed pin.
 *
 * - A NEW migration adds its line below, in the same PR. Until it merges it may still change; update its pin with it.
 * - A CHANGED digest of a merged migration means a shipped migration was edited: revert it and write the change as a
 *   new migration instead.
 */
class MigrationPinTest {

    /**
     * The migration chains' roots: `ledger.db`'s in the data layer, `downloads.db`'s in the services zone. A file's pin
     * is keyed by its path under its root, so moving a chain between modules keeps its pins.
     */
    private val chains = listOf(
        File(SourceScan.repoRoot, "domain/data/src/commonMain/sqldelight"),
        File(SourceScan.repoRoot, "domain/services/src/commonMain/sqldelight"),
    )

    /** Every migration, by its path under its root in [chains], and the SHA-256 of its bytes. */
    private val pins = mapOf(
        "download/app/snapsync/services/downloads/db/4.sqm" to
            "7aac4e93ed9dd1aa515699afd8f8f7ff8598183f653a5b9be77481bce169ffd8",
        "download/app/snapsync/services/downloads/db/5.sqm" to
            "c11a859ee1880006b5dd325b5c76c535df9cbc5535d2dd3d9d652dfc2c060bfa",
        // Re-pinned after it shipped: its `DROP COLUMN` failed on Android 11–13's SQLite, which no later migration can
        // fix since a fresh install runs this one; rebuilt to leave the same table (the file says why).
        "download/app/snapsync/services/downloads/db/6.sqm" to
            "68c3a7343a7507addda643b84857a0bc44d0b285f649b5d503438bd7b7116349",
        "download/app/snapsync/services/downloads/db/7.sqm" to
            "c5f9d683f65552aa5fa5144ed7c1c7e35d1ea9d8818d2817aee1d609b1552b7e",
        "download/app/snapsync/services/downloads/db/8.sqm" to
            "e25008999997ccaeaa0492557716948217f15127c85b7d7ffb4abb8097658237",
        "download/app/snapsync/services/downloads/db/9.sqm" to
            "acb19f0657a61342cd3c48e4f10cf4c2f9662270705ac4cfdb487cd4286f1791",
        // Not merged yet: the id columns read as a `Stored` (the `InvalidProgramState` change).
        "download/app/snapsync/services/downloads/db/10.sqm" to
            "c6b3f36f768099f74083ea66155ad3c3273d641cb4ca9a7bb21c46265bec45b7",
        // Not merged yet: a union read stores what the others declared (change `count-declared-photos`).
        "download/app/snapsync/services/downloads/db/11.sqm" to
            "8dbc12d5f9b9579d59f9d2b1d391aa5e144cafe8705f7e6c16b8234a07e9d6cf",
        "ledger/app/snapsync/data/store/db/11.sqm" to
            "bc378a8aecf83363863d1742286af7021f0c4d3ba591d09c194e010963763548",
        "ledger/app/snapsync/data/store/db/12.sqm" to
            "124dfb8345efa5745dff8df5a584933f86cb9bb0f6c8700d0b61ed7d2c5321b5",
        "ledger/app/snapsync/data/store/db/13.sqm" to
            "495f541590bff52cefbffc96817800604ccef42c95a8fbcbf1800e8a131b1bcf",
        "ledger/app/snapsync/data/store/db/14.sqm" to
            "39642ee5a2d20528e9c65c2b3798927d9b6cbb62633443438672c12704835ab3",
        "ledger/app/snapsync/data/store/db/15.sqm" to
            "47cb425ef439827ece704c3cad4dcbf205a6d8ef523142de8feddf47c19786e6",
        // Phase 4.2 (membership): re-types `ledgerRow` and `membership`, drops `membership.closed` and
        // `pendingLeave.received`.
        "ledger/app/snapsync/data/store/db/16.sqm" to
            "f365d5ba59fd34eb695a684b7fec94130f399573f7a3ab8e598d24100a1fd15f",
        // Not merged yet: the id columns read as a `Stored` (the `InvalidProgramState` change).
        "ledger/app/snapsync/data/store/db/17.sqm" to
            "0e02ac6aa4c4831be3c55745267c54402ed6edd57c2698403aa38b7344caaa03",
    )

    @Test
    fun `every migration is pinned and none has changed`() {
        val found = chains.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "sqm" }
                .map { it.relativeTo(root).invariantSeparatorsPath to sha256(it) }.toList()
        }.toMap()
        assertEquals(
            pins.keys.sorted(),
            found.keys.sorted(),
            "the migrations and their pins differ: a new migration adds its pin here; a pin without a file means a " +
                "shipped migration was deleted, which a device that never ran it can no longer reach",
        )
        pins.forEach { (path, pinned) ->
            assertEquals(
                pinned,
                found.getValue(path),
                "$path changed. If it has merged, it has shipped: revert the edit and write a new migration. " +
                    "If it has not merged yet, update its pin here.",
            )
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
