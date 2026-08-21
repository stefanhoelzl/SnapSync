package app.snapsync.logging

import app.snapsync.engine.LEDGER_APP_GROUP
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Where each process writes its device log (capability `diagnostic-logging`).
 *
 * The **app** writes its own `Documents/`[APP_LOG_FILE_NAME], exactly as it always has — a process can
 * always read its own container, so relocating it would buy no capability while breaking every
 * `pymobiledevice3 apps pull app.snapsync Documents/debug.log` in the runbook.
 *
 * The **extension** writes [EXTENSION_LOG_FILE_NAME] into the **shared App Group** container. That is
 * the one read the old placement made impossible: the two processes have separate sandboxes, so the
 * app cannot read the extension's `Documents/`, and the app is the process that assembles a
 * diagnostic dump. The App Group container is not USB-pullable, so the extension's log reaches a
 * cable via the `SNAPSYNC_EXPORT_LOGS` launch trigger (capability `ios-app-shell`), which copies it
 * into the app's `Documents/`.
 *
 * Resolution — including the fallback and the sentence that announces it — lives here rather than in
 * the composition roots, which hold no decisions (`module-architecture`, "Shells are wiring only").
 */
const val APP_LOG_FILE_NAME: String = "debug.log"

/** The extension's log file name, inside the App Group container. See [APP_LOG_FILE_NAME]. */
const val EXTENSION_LOG_FILE_NAME: String = "ext-debug.log"

/**
 * A resolved log-file location: the [path] a writer should append to (`null` when nothing writable
 * resolved), whether resolving it [fellBackToDocuments], and the boot line that says so.
 */
class LogDestination(val path: String?, val fellBackToDocuments: Boolean) {

    /**
     * The boot banner's destination line. A fallback means the App Group container was unavailable,
     * so the log is in that process's own `Documents/` and no diagnostic dump will carry it — which
     * has to be *said*, because a missing extension log otherwise reads as "the extension never ran",
     * and that is the usual suspect it would be confused with.
     */
    val bannerLine: String
        get() = when {
            path == null -> "[boot] log destination = NONE (no writable location resolved)"
            fellBackToDocuments -> "[boot] log destination = $path (FALLBACK: App Group unavailable)"
            else -> "[boot] log destination = $path"
        }
}

/** This process's `Documents/` directory, or `null` when the OS reports none. */
fun documentsDirectory(): String? =
    NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String

/** The shared App Group container, or `null` when the entitlement is missing or unprovisioned. */
fun appGroupDirectory(): String? =
    NSFileManager.defaultManager
        .containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)
        ?.path

/** The app process's log destination: its own `Documents/`[APP_LOG_FILE_NAME]. */
fun appLogDestination(): LogDestination =
    LogDestination(documentsDirectory()?.let { "$it/$APP_LOG_FILE_NAME" }, fellBackToDocuments = false)

/**
 * The extension process's log destination: [EXTENSION_LOG_FILE_NAME] in the App Group, falling back
 * to its own `Documents/`[APP_LOG_FILE_NAME] when the container is unavailable.
 *
 * The fallback is deliberate: the alternative — a writer that resolves to nothing — produces no log
 * at all, which is indistinguishable from a process that never ran.
 */
fun extensionLogDestination(): LogDestination {
    val group = appGroupDirectory()
    if (group != null) return LogDestination("$group/$EXTENSION_LOG_FILE_NAME", fellBackToDocuments = false)
    return LogDestination(documentsDirectory()?.let { "$it/$APP_LOG_FILE_NAME" }, fellBackToDocuments = true)
}

/**
 * Remove the extension's pre-relocation `Documents/`[APP_LOG_FILE_NAME] (and its rolled sibling), once.
 *
 * Idempotent by construction — after the removal there is nothing to remove. It matters because that
 * path keeps *succeeding* for `pymobiledevice3 apps pull app.snapsync.BackgroundUpload
 * Documents/debug.log` while returning a file frozen at the last pre-update run: silent staleness,
 * read as current, in the middle of a debugging session. A pull that returns nothing is honest.
 *
 * No-op while the writer is itself falling back to `Documents/` — that file is then the live log.
 */
@OptIn(ExperimentalForeignApi::class)
fun removeStaleExtensionDocumentsLog(destination: LogDestination) {
    if (destination.fellBackToDocuments) return
    val docs = documentsDirectory() ?: return
    val mgr = NSFileManager.defaultManager
    mgr.removeItemAtPath("$docs/$APP_LOG_FILE_NAME", error = null)
    mgr.removeItemAtPath("$docs/$APP_LOG_FILE_NAME.1", error = null)
}
