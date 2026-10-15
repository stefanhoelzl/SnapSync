package app.snapsync.data.store

import app.snapsync.contracts.at
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.upload.LedgerState
import app.snapsync.mock.DatabasesMock
import app.snapsync.ports.DbOpen
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `16.sqm` re-types `ledgerRow` and `membership` (the domain's `LedgerState` and `Direction`), drops the membership's
 * legacy `closed` and the pending leave's `received`. A migration keeps its rows: a database at v16, entered through the port, migrates on the store's own
 * open — the path an upgrading phone takes.
 */
class LedgerMigrationSixteenTest {

    private val device = DatabasesMock()

    @Test
    fun `an upgrade from v16 keeps the membership, every upload row and every leave owed`() {
        assertIs<DbOpen.Opened>(
            device.port().open(
                LEDGER_DB_NAME,
                LedgerDatabase.Schema.at(
                    16,
                    "INSERT INTO membership (id, eventId, name, minPhotoDate, startsAt, endsAt, maxPhotoDate, " +
                        "deletesAt, direction, saveToAlbum, closed, membersActive, membersSettled, keyId, openUntil, " +
                        "membersWaiting, deleted) VALUES (0, '$JOINED', 'Picnic', '2026-07-06T14:00:00Z', " +
                        "'2026-07-06T14:00:00Z', '2026-07-07T00:00:00Z', '2026-07-07T00:00:00Z', " +
                        "'2026-08-05T00:00:00Z', 'DownloadOnly', 1, 1, 3, 1, 'k1', NULL, 2, 0)",
                    "INSERT INTO ledgerRow (eventId, key, assetId, state) VALUES ('$JOINED', 'k', 'a', 'COMPLETED')",
                    "INSERT INTO pendingLeave (eventId, received) VALUES ('$LEFT', 1), ('$JOINED', 0)",
                ),
                readOnly = false,
            ),
        )

        val shared = SharedDatabase(device.port(), legacy = null)
        val membership = shared.use { it.membershipQueries.selectMembership().executeAsOne() }
        assertEquals(testEventId(JOINED), membership.eventId)
        assertEquals(Direction.DownloadOnly, membership.direction)
        assertEquals("k1", membership.keyId)
        assertEquals(2, membership.membersWaiting)
        val states = shared.use { db ->
            db.ledgerQueries.selectManifestRows(JOINED).executeAsList().map { it.state }
        }
        assertEquals(listOf(LedgerState.COMPLETED), states)
        val owed = shared.use { it.pendingLeaveQueries.selectPendingLeaves().executeAsList() }
        assertEquals(listOf(testEventId(JOINED), testEventId(LEFT)), owed, "every leave owed, received or not")
    }

    private companion object {
        const val JOINED = "11111111-1111-4111-8111-111111111111"
        const val LEFT = "22222222-2222-4222-8222-222222222222"
    }
}
