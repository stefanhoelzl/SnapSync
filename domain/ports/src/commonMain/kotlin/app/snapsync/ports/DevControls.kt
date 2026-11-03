package app.snapsync.ports

/**
 * **The build's development controls** — the inputs only a test build can carry (`docs/architecture.md`, "A
 * build-time-only module is contained by compilation"). A production build's adapter answers the inert values
 * forever and never delivers: the only adapter that does anything is the control channel's, compiled into a build
 * made with `-Psnapsync.rig=true` and into no other. So a shipped process cannot be switched, structurally rather
 * than by a runtime check.
 *
 * It delivers nothing: its reads are read at every use.
 */
interface DevControls : Port {
    /**
     * The per-uploader switch, read at every use (the channel changes it live). **Always `null` in a production
     * build**, so what a shipped process uploads with is a function of the device and its grant (decision record
     * `changes/both-uploaders-active`, D8).
     */
    fun uploaderPin(): UploaderPin?

    /**
     * Whether an event this device creates is PLAIN rather than encrypted (the encrypted file format,
     * `docs/architecture.md`), read at every create. **Always `true` in a production build for now**, which creates plain
     * events until encrypted creation is re-enabled (`PENDING_CLEANUPS.md`, "Re-enable encrypted creation"); a rig build
     * encrypts by default and answers `true` only when its channel switches encryption off. The change that retires
     * plain events deletes this read.
     */
    fun createsPlainEvents(): Boolean
}
