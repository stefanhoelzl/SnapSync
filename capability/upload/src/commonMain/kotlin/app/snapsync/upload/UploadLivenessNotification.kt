package app.snapsync.upload

/**
 * The name of the cross-process **Darwin** notification the PhotoKit upload extension posts after each
 * `process()` run to tell the main app "the ledger may have changed — re-read status" (spec: sync-status).
 * Payload-free; the app observes it foreground-only and re-reads the ledger counts. Shared here (a plain
 * string, platform-free) so the extension poster (`:app:ios:photokit-extension`) and the app observer
 * (`:app:ios`) name the exact same notification.
 */
const val UPLOAD_LIVENESS_DARWIN_NAME: String = "app.snapsync.upload.liveness"
