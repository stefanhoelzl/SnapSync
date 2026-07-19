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
 * [reloadConfig] re-reads the persisted membership into the config StateFlow first (migration step
 * 12, replacing the deleted protected-data defer ceremony — settled proof ④: zero deferrals across
 * all production logs, so the defer-queue was dead code): the import's own guards read that
 * StateFlow, and a wake landing before the first unlock — never observed in production — now runs
 * through and fails cleanly (the import's reads are caught below; the adapters distinguish
 * unreadable from absent, so nothing mints, clears, or leaves), converging at the next wake.
 * [refreshAttestation] is the wake-point token renewal — this BGTask is the one recurring wake that
 * does NOT depend on an upload having succeeded, which matters because an expired token is exactly
 * what stops uploads succeeding. Both are port/shell touches injected as `compose/`-built effect
 * lambdas; the re-arm, the OS task-completion handler, and the entry-point log wrap stay in the
 * shell.
 */
class DownloadBackstop(
    private val scope: CoroutineScope,
    private val downloadController: DownloadController,
    /** Re-read the persisted membership into the config StateFlow — the port touch, injected. */
    private val reloadConfig: () -> Unit,
    private val refreshAttestation: () -> Unit,
    private val log: Logger = Logger.withTag("DownloadBackstop"),
) {
    fun run() {
        reloadConfig()
        refreshAttestation()
        scope.launch {
            runCatching { downloadController.importReady() }
                .onFailure { log.w(it) { "download backstop import failed" } }
        }
    }
}
