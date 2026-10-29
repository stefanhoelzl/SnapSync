package app.snapsync.data.sharing

import app.snapsync.data.gallery.photoAccess
import app.snapsync.data.time.WallClock
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.sharing.AdmissionVerdict
import app.snapsync.domain.sharing.AdmittedAsset
import app.snapsync.domain.sharing.CandidateSet
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.Discovery
import app.snapsync.domain.sharing.ReadPlan
import app.snapsync.domain.sharing.SelectionInputs
import app.snapsync.domain.sharing.SelectionPolicy
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader

/**
 * **The member's own photos — the one place their contribution is decided.** A membership's [SelectionPolicy] is
 * derived here and nowhere else, its photos are read here under the read the grant allows, and admitted by that policy;
 * every consumer — the byte upload, the device manifest, the status total, the join preview, the album gather — takes
 * its answer from here, so none can judge a photo another way.
 *
 * In order: the grant decides the read ([ReadPlan]) before anything is read, the policy is derived for it (the album
 * denylist is read only when the library is walked), the read is made, and the policy admits it. Under a partial grant
 * there is no path to a walk: the selection is the gallery. The upload extension never reads a selection, so under a
 * partial grant its plan is that nothing can be read.
 *
 * [received] is echo suppression's set — the photos this device received through SnapSync — supplied by the caller
 * while its store is still the old zones' (MIGRATING; receiving moves it into the layers).
 */
class OwnPhotos(
    private val gallery: GalleryReader,
    private val walk: LibraryWalk,
    private val selection: SelectionSnapshots,
    private val albums: DenylistedAlbums,
    private val clock: WallClock,
) {

    /** What [membership] contributes now: its policy, and the read it is to be applied to. */
    suspend fun contribution(membership: Membership, received: Set<AssetId>): Contribution = contribution(
        membership.share.direction,
        membership.share.range.sharedAt(clock.now(), membership.event.range.start),
        received,
    )

    /**
     * What a membership of [direction] sharing [range] — as it is shared now — contributes: the policy, derived for the
     * read the grant allows (the album denylist is read only when the library is walked), paired with that read.
     */
    suspend fun contribution(direction: Direction, range: CaptureRange, received: Set<AssetId>): Contribution {
        val plan = plan()
        val denied = when (plan.readsAlbums(direction)) {
            true -> albums.members(range.from)
            false -> emptySet()
        }
        return Contribution(SelectionPolicy.of(SelectionInputs(direction, range, received, denied)), plan)
    }

    /** The read [contribution] is applied to, made: its candidates before admission, and the way to their resources. */
    suspend fun read(contribution: Contribution): Candidates = when (val plan = contribution.plan) {
        ReadPlan.Walk -> walked(walk.read(contribution.policy.walkBounds))
        is ReadPlan.Snapshot -> selected(plan.selection)
        ReadPlan.NotReadable -> unreadable
    }

    /** One read of the member's photos, admitted by [contribution]'s policy. */
    suspend fun discover(contribution: Contribution): DiscoveryRead {
        val read = read(contribution)
        return when (val verdict = contribution.policy.admit(read.candidates)) {
            is AdmissionVerdict.Discovered -> DiscoveryRead.Discovered(verdict.discovery, read.resources)
            AdmissionVerdict.NotReadable -> DiscoveryRead.NotReadable
        }
    }

    /**
     * How many of the member's photos [range] would share, sharing switched on — the preview a member sees while
     * choosing. It counts what WILL be shared: photos waiting for the event's start are counted.
     */
    suspend fun preview(range: CaptureRange, received: Set<AssetId>): PreviewRead =
        when (val read = discover(contribution(Direction.UploadOnly, range, received))) {
            is DiscoveryRead.Discovered -> PreviewRead.Counted(read.discovery.admitted.size)
            DiscoveryRead.NotReadable -> PreviewRead.NotReadable
        }

    /**
     * The assets [ids] names, resolved under the read the grant allows now: from the selection in hand under a partial
     * grant — a photo outside it is not this app's to upload, and resolves to nothing — and from the gallery under a
     * full one.
     */
    suspend fun resolve(ids: Set<AssetId>): GalleryRead<List<GalleryAssetResources>> =
        when (val plan = plan()) {
            ReadPlan.Walk -> GalleryResources(gallery).of(ids)
            is ReadPlan.Snapshot -> plan.selection.resources().of(ids)
            ReadPlan.NotReadable -> GalleryRead.NotReadable
        }

    private fun plan(): ReadPlan<SelectionState.Read> = ReadPlan.of(gallery.access().photoAccess(), selection.read)

    private fun walked(read: WalkRead): Candidates = when (read) {
        is WalkRead.Walked -> Candidates(CandidateSet.Walked(read.assets, read.takenAt), read.resources)
        WalkRead.NotReadable -> unreadable
    }

    private fun selected(state: SelectionState.Read): Candidates =
        Candidates(CandidateSet.Selected(state.facts(), clock.now()), state.resources())

    private val unreadable = Candidates(CandidateSet.NotReadable, GalleryResources(gallery))
}

/** A read's [candidates] before admission, and the way to their resources. */
class Candidates internal constructor(val candidates: CandidateSet, val resources: AssetResources)

/** What a membership contributes now: its [policy], paired with the read it is applied to. */
class Contribution internal constructor(
    val policy: SelectionPolicy,
    /** The read the grant allowed when the policy was derived — a selection's carries the selection it reads. */
    internal val plan: ReadPlan<SelectionState.Read>,
)

/** [OwnPhotos.discover]'s answer. */
sealed interface DiscoveryRead {
    /** The read, admitted — with the way to its admitted assets' resources. */
    class Discovered internal constructor(
        val discovery: Discovery,
        private val resources: AssetResources,
    ) : DiscoveryRead {
        /** The resources of [assets], from the read that admitted them: in hand for a selection, one walk request. */
        suspend fun resourcesOf(assets: List<AdmittedAsset>): GalleryRead<List<GalleryAssetResources>> =
            resources.of(assets.mapTo(linkedSetOf()) { it.facts.assetId })
    }

    /** Nothing could be read: no admitted set can be stated. */
    data object NotReadable : DiscoveryRead
}

/** [OwnPhotos.preview]'s answer. */
sealed interface PreviewRead {
    class Counted(val photos: Int) : PreviewRead
    data object NotReadable : PreviewRead
}
