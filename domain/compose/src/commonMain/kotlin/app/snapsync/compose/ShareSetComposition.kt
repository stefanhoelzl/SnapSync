package app.snapsync.compose

import app.snapsync.feature.membership.ShareSetLoad

/**
 * The join-time load (capability `upload-state-reconciliation`) over the app's ports: a provision into a new
 * membership makes the upload ledger its share set, from the device's stored-file listing.
 *
 * Composed in the APP on every tier. The load needs no `LedgerWriter`: `resetTo` and `clear` are the store's
 * reset family, owned by the membership use-cases, which a holder of the store may invoke whichever process's
 * cycle also records (capability `sync-ledger`, "Reader and writer capability split"; decision record
 * `changes/archive/2026-09-22-both-uploaders-active`).
 *
 * A top-level factory rather than an `AppCore` body because `AppCore` is measured: the `compose` tier's
 * `LargeClass` ceiling is what keeps that class from absorbing every composition in the graph.
 */
internal fun shareSetLoadFor(ports: AppPorts): ShareSetLoad = ShareSetLoad(
    files = ports.uploadRecord.files,
    ledger = ports.uploadRecord.ledger,
    deviceId = ports.deviceId,
    log = ports.log,
)
