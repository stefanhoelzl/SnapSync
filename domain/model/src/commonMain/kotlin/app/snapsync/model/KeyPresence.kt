package app.snapsync.model

/**
 * Whether this device holds the joined event's key (the encrypted file format, `docs/architecture.md`). Only [Lost]
 * stops anything: while it holds, nothing is uploaded or downloaded and the joined screen asks for the event's invite
 * (capability `sync-status`), until the invite is opened again (capability `join-event`).
 */
enum class KeyPresence {
    /** No key is needed: no membership, or a plain event's. */
    NotNeeded,

    /** The event's own key is kept. */
    Held,

    /**
     * The store could not be read — a device locked since it was started. Says nothing about the key, so it is never
     * shown and stops nothing beyond what an unreadable key already stops at each use.
     */
    Unknown,

    /** The event is encrypted and no key of its id is kept: restored onto a new device, or its protection reset. */
    Lost,
}
