package app.snapsync.ios

import app.snapsync.engine.LedgerWatcher
import app.snapsync.engine.iosLedgerBackend
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.status.LedgerSyncStatusSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The iOS composition root (D7): a single app-lifetime singleton that assembles the real live
 * stack. It owns a `SupervisorJob` scope on the main dispatcher so the source's collector and the
 * Orbit container outlive Compose recomposition (not a `rememberCoroutineScope`, which dies with
 * the view). The app has exactly one root screen, so process-lifetime ownership is correct; the
 * Swift entry point stays untouched. Move ownership to Swift only if scene-aware lifecycle or
 * scope recreation (multi-window, reset/logout) is ever needed.
 *
 * Assembly is lazy so it runs once on first view creation: ledger backend → watcher → source ×
 * PhotoKit permission → container. `permission` is passed as both ports (one adapter implements
 * both).
 */
object SnapSyncRoot {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val host: StatusContainerHost by lazy {
        val watcher = LedgerWatcher(iosLedgerBackend())
        val permission = PhotoLibraryPermission()
        val syncSource = LedgerSyncStatusSource(watcher, permission, scope)
        StatusContainerHost(syncSource, permission, permission, scope)
    }
}
