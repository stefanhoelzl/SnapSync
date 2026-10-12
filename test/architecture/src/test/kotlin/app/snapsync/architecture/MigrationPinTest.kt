package app.snapsync.architecture

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **A shipped migration is never edited** (capability `photo-sharing`; `domain/services/build.gradle.kts`).
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

    private val chains = File(SourceScan.repoRoot, "domain/services/src/commonMain/sqldelight")

    /** Every migration, by its path under [chains], and the SHA-256 of its bytes. */
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
        "ledger/app/snapsync/services/ledger/db/11.sqm" to
            "bc378a8aecf83363863d1742286af7021f0c4d3ba591d09c194e010963763548",
        "ledger/app/snapsync/services/ledger/db/12.sqm" to
            "124dfb8345efa5745dff8df5a584933f86cb9bb0f6c8700d0b61ed7d2c5321b5",
        "ledger/app/snapsync/services/ledger/db/13.sqm" to
            "495f541590bff52cefbffc96817800604ccef42c95a8fbcbf1800e8a131b1bcf",
        "ledger/app/snapsync/services/ledger/db/14.sqm" to
            "39642ee5a2d20528e9c65c2b3798927d9b6cbb62633443438672c12704835ab3",
    )

    @Test
    fun `every migration is pinned and none has changed`() {
        val found = chains.walkTopDown().filter { it.isFile && it.extension == "sqm" }
            .associate { it.relativeTo(chains).invariantSeparatorsPath to sha256(it) }
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
