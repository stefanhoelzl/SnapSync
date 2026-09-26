package app.snapsync.contracts

import app.snapsync.model.AssetId
import app.snapsync.model.LedgerEntry
import app.snapsync.services.ledger.LedgerService
import app.snapsync.model.LedgerState
import app.snapsync.model.ResourceRole
import app.snapsync.model.TerminalOutcome

import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The manifest version of the storage seam (capability `photo-sharing`, "The manifest version orders the
 * device's manifest snapshots"): a counter that advances on every change that could alter the device
 * manifest's projection, and on nothing else. Part of [LedgerStoreContract]'s clause list — a split for size
 * only, like [recordGuardClauses].
 *
 * "Advances" is asserted as "greater than", never as an exact step, except for the explicit bump: the SQLite
 * store advances once per row a statement touches, and the count is not part of the contract.
 */
internal fun ClauseList<LedgerStoreState, LedgerService>.manifestVersionClauses() {
    clause("a fresh store reads version zero", LedgerStoreState.EMPTY) { backend ->
        assertEquals(0L, backend.manifestVersion())
    }

    clause("an inserted row advances the version", LedgerStoreState.EMPTY) { backend ->
        backend.recordUnlessSettled(entry())
        assertTrue(backend.manifestVersion() > 0L)
    }

    clause("a deleted row advances the version", LedgerStoreState.EMPTY) { backend ->
        backend.recordUnlessSettled(entry())
        val before = backend.manifestVersion()
        backend.deleteKeys(listOf(entry().key))
        assertTrue(backend.manifestVersion() > before)
    }

    clause("a delete that matched nothing leaves the version alone", LedgerStoreState.EMPTY) { backend ->
        backend.recordUnlessSettled(entry())
        val before = backend.manifestVersion()
        backend.deleteKeys(listOf("never-recorded"))
        assertEquals(before, backend.manifestVersion())
    }

    clause("a detail backfill advances the version", LedgerStoreState.EMPTY) { backend ->
        // A bare row, as the join-time load seeds it: no capture date, no role, no detail.
        backend.resetTo(listOf(LedgerEntry("A-primary.heic", AssetId("A"), LedgerState.COMPLETED)))
        val before = backend.manifestVersion()
        backend.backfillManifestDetail(entry(key = "A-primary.heic", assetId = "A"))
        assertTrue(backend.manifestVersion() > before)
    }

    clause("a terminal write leaves the version alone", LedgerStoreState.EMPTY) { backend ->
        // The manifest carries no upload state; a bump per finished upload would republish every cycle.
        backend.recordUnlessSettled(entry(state = LedgerState.REQUESTED))
        val before = backend.manifestVersion()
        assertTrue(backend.markTerminal(entry().key, TerminalOutcome.COMPLETED))
        assertEquals(before, backend.manifestVersion())
    }

    clause("a record that changes only state or destination leaves the version alone", LedgerStoreState.EMPTY) { backend ->
        backend.recordUnlessSettled(entry(state = LedgerState.DISCOVERED))
        val before = backend.manifestVersion()
        backend.recordUnlessSettled(entry(state = LedgerState.REQUESTED, destinationPath = "/d"))
        assertEquals(before, backend.manifestVersion())
    }

    clause("a record that changes a projected field advances the version", LedgerStoreState.EMPTY) { backend ->
        backend.recordUnlessSettled(entry(state = LedgerState.DISCOVERED))
        val before = backend.manifestVersion()
        val live = LedgerEntry(
            entry().key, entry().assetId, LedgerState.DISCOVERED,
            creationDate = CREATION_DATE, role = ResourceRole.LIVE, contentType = "video/quicktime",
            originalFilename = "IMG_0001.MOV",
        )
        backend.recordUnlessSettled(live)
        assertTrue(backend.manifestVersion() > before)
    }

    clause("a declined record leaves the version alone", LedgerStoreState.EMPTY) { backend ->
        backend.recordUnlessSettled(entry(state = LedgerState.COMPLETED))
        val before = backend.manifestVersion()
        val stale = LedgerEntry(entry().key, AssetId("B"), LedgerState.DISCOVERED, creationDate = "2020-01-01T00:00:00Z")
        assertEquals(false, backend.recordUnlessSettled(stale), "a settled row is never overwritten")
        assertEquals(before, backend.manifestVersion())
    }

    clause("the reset family advances the version and never resets it", LedgerStoreState.EMPTY) { backend ->
        backend.recordUnlessSettled(entry())
        val afterRecord = backend.manifestVersion()
        backend.clear()
        val afterClear = backend.manifestVersion()
        assertTrue(afterClear > afterRecord, "clear deletes rows, so it advances")
        backend.resetTo(listOf(entry(key = "B"), entry(key = "C")))
        assertTrue(backend.manifestVersion() > afterClear, "resetTo inserts rows, so it advances")
    }

    clause("an explicit bump advances the version by exactly one", LedgerStoreState.EMPTY) { backend ->
        backend.recordUnlessSettled(entry())
        val before = backend.manifestVersion()
        backend.bumpManifestVersion()
        assertEquals(before + 1, backend.manifestVersion())
    }
}
