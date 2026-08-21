package app.snapsync.rig

import kotlinx.serialization.Serializable

/**
 * `GET /device/gallery`'s body — the library, and what the selection policy makes of it.
 *
 * In `commonMain` while the reader that fills it is iOS-only, for the same reason [RigState] is: the
 * encoder is compiler-generated from the declaration, so there is no hand-written rendering that could
 * disagree with the values, and a second platform brings its own reader rather than its own shape.
 */
@Serializable
data class GalleryView(
    val census: CensusView,
    /**
     * The photo grant, reported beside the counts rather than left to a second request.
     *
     * Under `LIMITED` the census is the hand-picked **selection**, not the library — `total: 12` on a
     * 9525-photo phone means a small selection, and nothing else in this body would say so.
     */
    val grant: String,
    /** Absent when no `cutoff` was given: the census alone answers "what does this library hold". */
    val policy: PolicyView?,
)

/**
 * The raw library counts, fetched with **no** predicate.
 *
 * [screenshots] and [screenRecordings] use the SELECT form (`(mediaSubtypes & N) != 0`) rather than the
 * exclusion form the production fetch uses, because they answer a question the policy-scoped read
 * structurally cannot: the production predicate drops these before enumeration, so they never reach a
 * policy-scoped count. Non-zero values are the only evidence available that the subtype bits match real,
 * OS-generated assets — which a synthesized library cannot demonstrate, since `PHAssetCreationRequest`
 * cannot set a subtype.
 */
@Serializable
data class CensusView(val total: Long, val screenshots: Long, val screenRecordings: Long)

/** What the policy admitted for a given cutoff, and what it cost to find out. */
@Serializable
data class PolicyView(
    val cutoff: String,
    val admitted: Int,
    val excluded: Int,
    /** Whether resources were read. `false` means [AssetView.resources] is absent, not empty. */
    val readResources: Boolean,
    /**
     * What this read actually paid. Reported because a resource read is ~110 ms per asset on an SE2, so a
     * slow response is explained by the body rather than looking like a hang.
     */
    val elapsedMs: Long,
    /**
     * Every asset the walk returned — admitted and refused alike, unbounded.
     *
     * Deliberately not capped. A silent truncation reads as "this is the whole library", which is the
     * absence-collapse this repo keeps paying for; [CensusView.total] sits beside these rows so a reader
     * can confirm nothing was dropped.
     */
    val assets: List<AssetView>,
)

/**
 * One asset, its policy inputs, and the verdict.
 *
 * The facts are the ones the rules actually decide on, so a reader can check the verdict against them in
 * the same row — `refusedBy: "MinImageArea(3000000)"` next to `pixelArea: 4096` explains itself.
 */
@Serializable
data class AssetView(
    val assetId: String,
    val captureDate: String,
    val pixelArea: Long?,
    val isScreenshot: Boolean,
    val isScreenRecording: Boolean,
    val isVideo: Boolean,
    val isEdited: Boolean,
    val admitted: Boolean,
    /** The FIRST rule that refused, named in the rule's own vocabulary. `null` when admitted. */
    val refusedBy: String?,
    /**
     * The asset's resources, present only when asked for AND the asset was admitted.
     *
     * [ResourceView.filename] is the reason to ask: it IS the upload/ledger key, so it answers "what
     * filename will this upload under" and "why did one asset produce two ledger rows" — neither of which
     * anything else on the device can answer.
     */
    val resources: List<ResourceView>?,
)

/**
 * One platform resource of an asset.
 *
 * No role field: `Resource` carries none. The adapter resolves the platform's `PHAssetResourceType` into a
 * role during mapping and drops the resources whose role is not one we upload, so by the time a resource
 * exists here the role has already done its work and been discarded.
 */
@Serializable
data class ResourceView(val contentType: String, val filename: String)
