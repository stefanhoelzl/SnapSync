package app.snapsync.flow

import app.snapsync.feature.download.DownloadController
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The **download import-tail backstop** OS-callback trigger flow (capability `photo-download`, 5.4):
 * drain any staged-but-not-yet-imported foreign assets when no further download event would wake the
 * app (e.g. the last transfer overran its URLSession wake budget). It imports already-downloaded work;
 * it does not re-read the union (discovery stays foreground-only).
 *
 * [protectedDataGate] defers the import to the next unlock when protected data is unreadable (the
 * import reads the download store + PhotoKit + the album map) — deferring rather than failing, and
 * without touching the Keychain, which is what once minted a device id and aborted the process.
 * [refreshAttestation] is the wake-point token renewal — this BGTask is the one recurring wake that
 * does NOT depend on an upload having succeeded, which matters because an expired token is exactly what
 * stops uploads succeeding. Both are the shell's, injected as `compose/`-built effect lambdas; the
 * re-arm, the OS task-completion handler, and the entry-point log wrap stay in the shell.
 */
class DownloadBackstop(
    private val scope: CoroutineScope,
    private val downloadController: DownloadController,
    private val protectedDataGate: (tag: String, work: () -> Unit) -> Unit,
    private val refreshAttestation: () -> Unit,
    private val log: Logger = Logger.withTag("DownloadBackstop"),
) {
    fun run() {
        protectedDataGate("runDownloadBackstop") {
            refreshAttestation()
            scope.launch {
                runCatching { downloadController.importReady() }
                    .onFailure { log.w(it) { "download backstop import failed" } }
            }
        }
    }
}
