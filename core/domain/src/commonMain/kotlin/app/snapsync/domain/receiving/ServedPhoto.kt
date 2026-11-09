package app.snapsync.domain.receiving

import app.snapsync.domain.asset.CaptureDate

/**
 * What a resource's served name came to (decision record `changes/seal-file-names`, D6): a plain event's as served, an
 * encrypted event's opened with its key for the one photo it is sealed for.
 */
sealed interface ServedFileName {
    class Named(val name: SenderFilename) : ServedFileName

    /** Sealed, and it does not open under the event's key or opens to no file: [reason] says which. */
    class Unopened(val reason: String) : ServedFileName

    /** A plain event's empty name — no upload names none. */
    data object Malformed : ServedFileName
}

/** What one resource of a served photo came to, its name read as its event serves it. */
sealed interface ServedResource {
    /** Its name read — readable, or opened with the event's key. */
    class Named(val resource: UnionResource) : ServedResource

    /** A sealed name that does not open under the event's key, or opens to no file: [reason] says which. */
    class Unopened(val reason: String) : ServedResource

    /** No name at all where one is always served: a page this build cannot go on from. */
    data object Malformed : ServedResource
}

/**
 * What one served photo comes to: received with every resource named; **left out** when a sealed name of it does not
 * open — never received, while every other photo of the page is; or malformed, which no page this build can read
 * carries.
 */
sealed interface ServedPhoto {
    class Read(val photo: UnionPhoto) : ServedPhoto

    /** Left out of the page, [reasons] why its sealed names did not open. */
    class Left(val ref: AssetRef, val reasons: List<String>) : ServedPhoto

    data object Malformed : ServedPhoto

    companion object {
        /** [ref]'s photo, captured at [captured], from what each of its [resources] came to. */
        fun of(ref: AssetRef, captured: CaptureDate, resources: List<ServedResource>): ServedPhoto = when {
            resources.any { it is ServedResource.Malformed } -> Malformed
            resources.any { it is ServedResource.Unopened } ->
                Left(ref, resources.filterIsInstance<ServedResource.Unopened>().map { it.reason })
            else -> Read(
                UnionPhoto(ref, captured, resources.filterIsInstance<ServedResource.Named>().map { it.resource }),
            )
        }

        /** The photos a page serves, those left out dropped — `null` when one is malformed: no page to go on from. */
        fun pageOf(photos: List<ServedPhoto>): List<UnionPhoto>? = when {
            photos.any { it is Malformed } -> null
            else -> photos.filterIsInstance<Read>().map { it.photo }
        }
    }
}
