package app.snapsync.compose

import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.album.AlbumGather
import app.snapsync.model.EventConfig
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.ports.EntryContext
import app.snapsync.services.gallery.GalleryAccessState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The event album's gather (capability `event-album`) over the app's ports. Called only from [AppCore]: the
 * extension's composition ([uploadCore]) has no such call, which is what keeps "the extension never gathers"
 * true by construction. [policyFor] is the app's one membership-policy derivation, so the gather admits own
 * photos exactly as the manifest and the status total do. [scope] is the composition scope, whose dispatcher
 * is the composition lane — never the UI lane, because the platform add blocks its thread.
 */
internal fun albumGather(
    services: AppServices,
    entryContext: EntryContext,
    access: GalleryAccessState,
    coordinator: AlbumCoordinator,
    scope: CoroutineScope,
    policyFor: suspend (EventConfig) -> SelectionPolicy,
): AlbumGather = AlbumGather(
    configSource = services.config,
    ledger = services.ledger,
    policyFor = policyFor,
    downloads = services.downloadStore,
    photoAccess = access,
    coordinator = coordinator,
    scope = scope,
    entryContext = entryContext,
)

/**
 * The event album's permission-grant subscription, launched on [this] composition scope by
 * `AppCore.installPermissionSubscriptions` only — never on mere construction, so a cold background wake starts
 * nothing off the permission `StateFlow`'s replay.
 *
 * The app is the sole album creator, and sync needs the same grant, so the album exists before the first
 * synced photo — both processes then only ADD (capability `event-album`). Unconditional call: the
 * membership's opt-in gate is the coordinator's own guard. Usable access (`grantsPhotoAccess`): album
 * creation works under a LIMITED grant (measured — capability `photo-access`), so a limited member's
 * opted-in album exists before their first import lands.
 *
 * The album gather rides the SAME collector, after the ensure; the gather decides whether this emission is an
 * in-process grant. A second collector would ensure the album concurrently with this one — two creations for
 * one album-less membership.
 */
internal fun CoroutineScope.launchAlbumGrantSubscription(
    services: AppServices,
    coordinator: AlbumCoordinator,
    gather: AlbumGather,
): Job = launch {
    services.ports.photoAccess.permission.collect { status ->
        if (status.grantsPhotoAccess) {
            services.config.config.value?.let { cfg ->
                // Not an opt-in: a launch's replay never brings back a folder album the member emptied.
                coordinator.ensureAlbum(cfg.eventId, cfg.name, cfg.saveToAlbum, optIn = false)
            }
        }
        gather.onAccessObserved(status.grantsPhotoAccess)
    }
}
