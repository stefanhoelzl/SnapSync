package app.snapsync.application.album

import app.snapsync.application.EntryPointCall
import app.snapsync.data.album.AlbumCreation
import app.snapsync.data.album.AlbumFiling
import app.snapsync.data.album.AlbumMap
import app.snapsync.data.album.NewAlbum
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.album.AlbumAsk
import app.snapsync.domain.album.AlbumEntry
import app.snapsync.domain.album.AlbumWishVerdict
import app.snapsync.domain.album.EventAlbumId
import app.snapsync.domain.album.StoredAlbumVerdict
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.of

/**
 * Make sure the joined membership's album exists, as far as the member wants one and the grant allows: the album the
 * map holds is reused while it is there — a rejoin keeps its photos — and one the member deleted comes back only on
 * their opt-in ([AlbumAsk]). The app's alone (`@AppProcessOnly`): the one creator, so two processes never make two
 * albums.
 */
class EnsureEventAlbum @AppProcessOnly constructor(
    private val map: AlbumMap,
    private val filing: AlbumFiling,
    private val creation: AlbumCreation,
) {
    private val log = Log("EnsureEventAlbum")

    @EntryPointCall
    suspend operator fun invoke(membership: Membership.Active, access: PhotoAccess, ask: AlbumAsk): EnsureAlbumOutcome =
        when (AlbumWishVerdict.of(membership.share.album, access)) {
            AlbumWishVerdict.Wanted -> wanted(membership, ask)
            AlbumWishVerdict.NotWanted -> EnsureAlbumOutcome.NotWanted
            AlbumWishVerdict.NoAccess -> EnsureAlbumOutcome.NoAccess
        }

    private suspend fun wanted(membership: Membership.Active, ask: AlbumAsk): EnsureAlbumOutcome =
        when (val entry = map.entry(membership.event.id)) {
            is AlbumEntry.Mapped -> stored(membership, entry, ask)
            AlbumEntry.None -> created(membership)
            AlbumEntry.Unreadable -> EnsureAlbumOutcome.Unreadable.also {
                log.w("no album made: the album map cannot be read", "event" of membership.event.id)
            }
        }

    private suspend fun stored(membership: Membership.Active, entry: AlbumEntry.Mapped, ask: AlbumAsk) = when (
        val stored = StoredAlbumVerdict.of(entry.album, standing(entry), ask)
    ) {
        is StoredAlbumVerdict.Reuse -> reused(membership, stored.album)
        StoredAlbumVerdict.Recreate -> created(membership)
        StoredAlbumVerdict.KeptDeleted -> keptDeleted(membership)
    }

    private suspend fun reused(membership: Membership.Active, album: EventAlbumId) =
        EnsureAlbumOutcome.Reused(album).also {
            log.i("album reused", "event" of membership.event.id, "album" of album.value)
        }

    private suspend fun keptDeleted(membership: Membership.Active) = EnsureAlbumOutcome.KeptDeleted.also {
        log.i("album deleted by the member, not recreated without an opt-in", "event" of membership.event.id)
    }

    private suspend fun standing(entry: AlbumEntry.Mapped) =
        filing.contents.standing(entry.fill, filing.presence(entry.album))

    private suspend fun created(membership: Membership.Active): EnsureAlbumOutcome =
        when (val made = creation.create(membership.event.name)) {
            is NewAlbum.Created -> remembered(membership, made.album)
            NewAlbum.NotCreated -> EnsureAlbumOutcome.NotCreated.also {
                log.w("album creation failed", "event" of membership.event.id)
            }
        }

    private suspend fun remembered(membership: Membership.Active, album: EventAlbumId): EnsureAlbumOutcome {
        val event = membership.event.id
        when (val written = map.remember(event, album)) {
            StoreWrite.Written -> log.i("album created", "event" of event, "album" of album.value)
            is StoreWrite.Failed -> log.w("album created, not remembered", "event" of event, "why" of written.detail)
        }
        return EnsureAlbumOutcome.Created(album)
    }
}

/** What ensuring the album came to. */
sealed interface EnsureAlbumOutcome {
    data class Reused(val album: EventAlbumId) : EnsureAlbumOutcome

    data class Created(val album: EventAlbumId) : EnsureAlbumOutcome

    /** The member chose no album. */
    data object NotWanted : EnsureAlbumOutcome

    /** No usable photo access: no album can be made yet. */
    data object NoAccess : EnsureAlbumOutcome

    /** The member deleted it and did not ask for it again. */
    data object KeptDeleted : EnsureAlbumOutcome

    /** The map could not be read: none made, so none sits beside one the map lost sight of. */
    data object Unreadable : EnsureAlbumOutcome

    /** The library made none. */
    data object NotCreated : EnsureAlbumOutcome
}
