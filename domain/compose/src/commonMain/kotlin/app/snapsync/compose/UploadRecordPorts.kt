package app.snapsync.compose

import app.snapsync.ports.DeviceFilesSource
import app.snapsync.ports.LedgerStore

/**
 * What this process knows about its own uploads — the two seams that only mean anything together (spec
 * `module-architecture`, "One shared composition").
 *
 * A **cohesive sub-bundle** of [AppPorts], which the `compose` complexity tier's config names as the way
 * that bundle's parameter ceiling comes down. The [ledger] records what this device believes it uploaded;
 * [files] is what the backend reports it actually holds. The join-time load takes exactly these two.
 *
 * The app **reads** through this bundle — the status counts' per-asset progress read and the diagnostic
 * dump's aggregates — and **resets** through it at membership transitions: the join-time load `resetTo`s
 * (or clears) the ledger, and a leave clears it (capability `upload-state-reconciliation`). Both resets are
 * the store's reset family, owned by the membership use-cases — each one guarded transaction (capability
 * `sync-ledger`, "Reader and writer capability split"). Records are the upload cycle's own, through its
 * `LedgerWriter`, in whichever process runs it. Nothing composed over this bundle records a row.
 */
class UploadRecordPorts(
    /** The app-side ledger handle: the reads above, and the reset family at join and leave. */
    val ledger: LedgerStore,
    /**
     * The per-device stored-file listing (`bunny-list-endpoint`) the join-time load seeds from. The upload
     * cycle no longer reads it on either tier; the app binds its own `HttpDeviceFilesSource`.
     */
    val files: DeviceFilesSource,
)
