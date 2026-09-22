package app.snapsync.contracts

import app.snapsync.model.LedgerState
import app.snapsync.model.Resource
import app.snapsync.model.toLedgerRow
import app.snapsync.ports.LedgerStore

/**
 * Seed a `COMPLETED` row for [resource] — a test's way of saying "these bytes are already stored".
 *
 * Test-only on purpose. No production writer records `COMPLETED`: a completion is a fact the platform reports
 * through the guarded terminal write, and a stored resource is seeded by the join-time load's
 * `resetTo` (capability `sync-ledger`). The row is built from the resource the same way the writer builds
 * one, so no call site re-states a row's columns by hand.
 */
suspend fun LedgerStore.seedCompleted(resource: Resource) =
    recordUnlessSettled(resource.toLedgerRow(LedgerState.COMPLETED))
