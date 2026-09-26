package app.snapsync.compose

import app.snapsync.ports.LedgerStore

/**
 * What this process knows about its own uploads: the ledger (spec `docs/architecture.md`, "One shared
 * composition").
 *
 * A **cohesive sub-bundle** of [AppPorts], which the `compose` complexity tier's config names as the way
 * that bundle's parameter ceiling comes down. The [ledger] records what this device believes it uploaded; what the
 * backend reports it actually holds is the device-files backend service, composed over the app's one authenticated
 * backend. The join-time load takes exactly these two.
 *
 * The app **reads** through this bundle — the status counts' per-asset progress read and the diagnostic
 * dump's aggregates — and **resets** through it at membership transitions: the join-time load `resetTo`s
 * (or clears) the ledger, and a leave clears it (capability `photo-sharing`). Both resets are
 * the store's reset family, owned by the membership use-cases — each one guarded transaction (capability
 * `photo-sharing`, "Reader and writer capability split"). Records are the upload cycle's own, through its
 * `LedgerWriter`, in whichever process runs it. Nothing composed over this bundle records a row.
 */
class UploadRecordPorts(
    /** The app-side ledger handle: the reads above, and the reset family at join and leave. */
    val ledger: LedgerStore,
)
