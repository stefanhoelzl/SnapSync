package app.snapsync.compose

import app.snapsync.feature.membership.ShareSetLoad

/**
 * The join-time load (capability `upload-state-reconciliation`) over the app's ports: a provision into a new
 * membership makes the upload ledger its share set, from the device's stored-file listing.
 *
 * Composed in the APP on every tier. On iOS >=26.1 the app holds no `LedgerWriter` — the extension is the
 * single record-writer — and the load needs none: `resetTo` and `clear` are the store's reset family, which a
 * non-writer holder may invoke (capability `sync-ledger`, "Reader and writer capability split").
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
