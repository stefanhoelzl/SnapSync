package app.snapsync.data.sharing

import app.snapsync.data.gallery.facts
import app.snapsync.data.gallery.platformId
import app.snapsync.data.time.WallClock
import app.snapsync.domain.asset.AssetFacts
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.WalkBounds
import app.snapsync.domain.time.Timestamp
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryChangeTokenRead
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The resources of a read's assets — the platform request the cost ladder defers until an asset is admitted. */
interface AssetResources {
    /** The resources of the assets [ids] names, in one request; an asset that is gone simply has none. */
    suspend fun of(ids: Set<AssetId>): GalleryRead<List<GalleryAssetResources>>
}

/** Resources read from the gallery by id, one batched request for every admitted asset. */
class GalleryResources(private val gallery: GalleryReader) : AssetResources {
    override suspend fun of(ids: Set<AssetId>): GalleryRead<List<GalleryAssetResources>> =
        gallery.resources(ids.mapTo(linkedSetOf()) { it.platformId() })
}

/** What a walk of the library produced. */
sealed interface WalkRead {
    /** The library's assets inside the walk's bounds (a superset allowed, an omission never), read from [takenAt]. */
    class Walked(val assets: List<AssetFacts>, val takenAt: Timestamp, val resources: AssetResources) : WalkRead

    /** The gallery could not be read. */
    data object NotReadable : WalkRead
}

/**
 * A walk of the member's library within capture-date bounds — asked only under a full grant. Two kinds, by process:
 * [GalleryWalk] reads every time; [MemoizedWalk], the app's, answers an unchanged library from its last walk.
 */
interface LibraryWalk {
    /** The library within [bounds]; [WalkBounds.None] reads nothing — the policy admits nothing. */
    suspend fun read(bounds: WalkBounds): WalkRead
}

/** The walk itself: one facts read of the library narrowed to the bounds, resources deferred to [GalleryResources]. */
class GalleryWalk(private val gallery: GalleryReader, private val clock: WallClock) : LibraryWalk {

    override suspend fun read(bounds: WalkBounds): WalkRead {
        val takenAt = clock.now()
        return when (bounds) {
            WalkBounds.None -> WalkRead.Walked(emptyList(), takenAt, GalleryResources(gallery))
            is WalkBounds.Within -> walk(bounds.range, takenAt)
        }
    }

    private suspend fun walk(bounds: CaptureRange, takenAt: Timestamp): WalkRead =
        when (val read = gallery.assets(AssetFilter(bounds.from.at.at, bounds.until.at.at))) {
            GalleryRead.NotReadable -> WalkRead.NotReadable
            is GalleryRead.Read -> WalkRead.Walked(read.value.map { it.facts() }, takenAt, GalleryResources(gallery))
        }
}

/**
 * The app process's **walk memo** (decision record `changes/own-work-per-wake`, D9): a walk answered from the last one
 * while the library is unchanged.
 *
 * An entry is keyed on the library's change token ([LibraryChangeToken.sameLibraryAs]) and the walk's bounds — what
 * the platform's fetch is built from. It holds a read, never an admission: the policy judges a served read afresh.
 * Only a read that succeeded is kept, and the token is read before the walk, so a change landing while the walk runs
 * leaves the stored token older than the library and the next read walks again — never the reverse. Served, the read
 * is as of now: the library is unchanged since it was taken.
 *
 * In memory only, and **never in the upload extension** (its 32 MB limit, and nothing held across `process()` calls):
 * the constructor is [AppProcessOnly]. Its soundness — every change outside the process moves the token — is measured
 * (`changes/own-work-per-wake`, task 7.4: the SE2 on iOS 26.6.2, and 15/15 external changes on the simulator).
 */
class MemoizedWalk @AppProcessOnly constructor(
    private val walk: LibraryWalk,
    private val token: LibraryChangeTokenRead,
    private val clock: WallClock,
) : LibraryWalk {

    private class Entry(val token: LibraryChangeToken, val bounds: WalkBounds, val read: WalkRead.Walked) {
        fun matches(token: LibraryChangeToken, bounds: WalkBounds): Boolean =
            this.bounds == bounds && this.token.sameLibraryAs(token)
    }

    /** Guards [entry] only — never a walk, so two callers never wait on each other's enumeration. */
    private val lock = Mutex()
    private var entry: Entry? = null
    private val log = Log("WalkMemo")

    override suspend fun read(bounds: WalkBounds): WalkRead {
        val now = token.changeToken() ?: return walk.read(bounds)
        return remembered(now, bounds)
    }

    private suspend fun remembered(token: LibraryChangeToken, bounds: WalkBounds): WalkRead {
        val held = held(token, bounds) ?: return fresh(token, bounds)
        return served(held.read)
    }

    private suspend fun held(token: LibraryChangeToken, bounds: WalkBounds): Entry? =
        lock.withLock { entry }?.takeIf { it.matches(token, bounds) }

    private suspend fun served(read: WalkRead.Walked): WalkRead {
        log.i("library unchanged, answered without a walk", "candidates" of read.assets.size)
        return WalkRead.Walked(read.assets, clock.now(), read.resources)
    }

    private suspend fun fresh(token: LibraryChangeToken, bounds: WalkBounds): WalkRead {
        val read = walk.read(bounds)
        lock.withLock {
            entry = when (read) {
                is WalkRead.Walked -> Entry(token, bounds, read)
                WalkRead.NotReadable -> entry
            }
        }
        return read
    }
}
