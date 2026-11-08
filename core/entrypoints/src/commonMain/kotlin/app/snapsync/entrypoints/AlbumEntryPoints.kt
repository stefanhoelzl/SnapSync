@file:OptIn(EntryPointCall::class)

package app.snapsync.entrypoints

import app.snapsync.application.EntryPointCall
import app.snapsync.application.album.AlbumPhotosRead
import app.snapsync.application.album.EnsureEventAlbum
import app.snapsync.application.album.OwnAlbumPhotos
import app.snapsync.application.album.PlaceOwnPhotos
import app.snapsync.application.album.PlaceReceivedPhotos
import app.snapsync.application.album.ReceivedAlbumPhotos
import app.snapsync.data.album.AccessChange
import app.snapsync.data.gallery.GrantRead
import app.snapsync.domain.album.AlbumAsk
import app.snapsync.domain.album.AlbumWishVerdict
import app.snapsync.domain.album.GrantVerdict
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.membership.Membership
import app.snapsync.entrypoints.MembershipEntryPoints.membership
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.scheduling.BackgroundEntryPoint
import app.snapsync.scheduling.Run
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.Sequence

/**
 * The photo grant changed — or the app's UI launch took its first look at it — for the joined membership's album:
 * ensured whenever access is usable, so a member who chose the album before granting access finds it made, and
 * gathered into once access became usable while the app runs (`GrantVerdict`). Never an opt-in, so a launch never
 * brings back an album the member deleted. Followed one emission at a time, so two ensures never race.
 */
class OnPhotoAccessChanged @AppProcessOnly constructor(
    runtime: Runtime,
    private val membership: MembershipBranch,
    private val ensure: EnsureEventAlbum,
    private val gathering: AlbumGathering,
) : BackgroundEntryPoint<AccessChange>(runtime) {
    override suspend fun run(arg: AccessChange, on: Run) = on.steps {
        membership(membership, Freshness.Cached) {
            active { joined ->
                when (GrantVerdict.of(arg.before, arg.now)) {
                    GrantVerdict.Nothing -> Unit
                    GrantVerdict.EnsureOnly -> isolated("album") { ensure(joined, arg.now, AlbumAsk.Replay) }
                    GrantVerdict.EnsureAndGather -> {
                        isolated("album") { ensure(joined, arg.now, AlbumAsk.Replay) }
                        isolated("album gather") { gathering.requested() }
                    }
                }
            }
        }
    }
}

/**
 * A gather of what the device already holds into the joined membership's album, started and never awaited — a join's,
 * a Save's, or access becoming usable — under its own name: a join and a Save must not wait on a cost that grows with
 * the photos held.
 */
class AlbumGathering(private val runtime: Runtime, private val pass: AlbumGatherPass) {
    suspend fun requested() = runtime.detached("OnAlbumGather") { run -> run.steps { run(pass) } }
}

/**
 * **The gather**: the photos this device already holds for the joined event, placed in its album — the received ones
 * (on a folder phone, MOVED out of the camera folder), and the member's own wherever they go into an album. One pass
 * at a time; requests while one runs join exactly one more, which reads the membership again, so a Save landing
 * mid-gather is honoured. It keeps no record of what it placed: adding a photo already in the album is a no-op
 * (measured, simulator, iOS 26.5), so a repeat places nothing twice. Batched by [BATCH_SIZE]. The app's alone
 * (`@AppProcessOnly`): the upload extension never gathers.
 */
class AlbumGatherPass @AppProcessOnly constructor(
    private val membership: MembershipBranch,
    private val grant: GrantRead,
    private val received: ReceivedAlbumPhotos,
    private val own: OwnAlbumPhotos,
    private val placeOwn: PlaceOwnPhotos,
    private val placeReceived: PlaceReceivedPhotos,
) : Sequence<Unit>("album-gather") {
    private val log = Log("AlbumGather")

    override suspend fun pass(on: Run) = on.steps {
        membership(membership, Freshness.Cached) {
            active { joined ->
                when (AlbumWishVerdict.of(joined.share.album, grant.access())) {
                    AlbumWishVerdict.Wanted -> required { receivedHalf(joined, on) }
                    AlbumWishVerdict.NotWanted -> required { log.i("gather skipped: no album chosen") }
                    AlbumWishVerdict.NoAccess -> required { log.i("gather skipped: no usable photo access") }
                }
            }
        }
    }

    private suspend fun receivedHalf(joined: Membership.Active, on: Run) = on.steps {
        when (val read = required { received(joined.event.id) }) {
            is AlbumPhotosRead.Read -> required { ownHalf(joined, read.assets, on) }
            is AlbumPhotosRead.Unavailable ->
                required { log.w("gather skipped: what was received cannot be read", "why" of read.detail) }
        }
    }

    private suspend fun ownHalf(joined: Membership.Active, receivedAssets: List<AssetId>, on: Run) = on.steps {
        when (val read = required { own(joined) }) {
            is AlbumPhotosRead.Read -> required { placed(joined, read.assets, receivedAssets, on) }
            is AlbumPhotosRead.Unavailable -> {
                isolated(
                    "own rows",
                ) { log.w("gather places no own photo: its rows cannot be read", "why" of read.detail) }
                required { placed(joined, emptyList(), receivedAssets, on) }
            }
        }
    }

    private suspend fun placed(joined: Membership.Active, mine: List<AssetId>, others: List<AssetId>, on: Run) =
        on.steps {
            required { log.i("gather placing", "own" of mine.size, "received" of others.size) }
            mine.chunked(BATCH_SIZE).forEach { batch -> isolated("own photos") { placeOwn(joined, batch) } }
            others.chunked(BATCH_SIZE).forEach { batch ->
                isolated("received photos") { placeReceived(joined.event.id, batch) }
            }
        }

    companion object {
        /**
         * How many photos one library change takes. The platform's add blocks its thread for the whole change and was
         * measured LINEAR, about 0.65 ms per photo on an iOS 26 simulator, so the size changes no gather's total cost —
         * it only bounds each change: about 0.3 s at 500 (decision record `2026-09-21-album-gathers-retroactively` D5).
         */
        const val BATCH_SIZE: Int = 500
    }
}
