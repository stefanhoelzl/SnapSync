package app.snapsync.levers

import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **The photo library operator's face**: what the person holding the phone does to their library, how the library
 * itself answers the app (a walk held or failed, an import refused or parked, an add held), and what a test reads of
 * it. The photo library mock's operator implements it; a host whose library is real refuses every method — a real
 * library has no switch for how it answers, and its photos are the member's.
 *
 * A photo is named by the identifier the library holds it under ([HeldPhoto.id]); an album the app made by the id the
 * library gave it ([AppAlbum.id]).
 */
@Rpc
interface LibraryLevers {
    // ---- the library ------------------------------------------------------------------------------

    /**
     * The person takes the photo [id], of [kind] (omitted: a camera photo), captured at [date] (omitted:
     * 2026-06-01T10:00:00Z): it is in the library from now on.
     */
    suspend fun add(id: String, kind: LibraryPhotoKind? = null, date: String? = null)

    /** The person deletes the photo [asset] from the library. */
    suspend fun remove(asset: String)

    // ---- the grant --------------------------------------------------------------------------------

    /** The grant, as the person set it in Settings. */
    suspend fun permission(grant: PhotoGrant)

    /** What the person answers the permission dialog with, the one time it is asked. */
    suspend fun requestAnswer(grant: PhotoGrant)

    /**
     * Under a partial grant, the person's selection is now exactly [assets] — the photos of the library with those ids,
     * as the picker's outcome delivers it to the running app. Refused while no selection observer is open: a process
     * whose observer is closed would hear nothing.
     */
    suspend fun changeSelection(assets: List<String>)

    // ---- the library's answers --------------------------------------------------------------------

    /** The next walk of the library fails, as a platform walk can. */
    suspend fun failNextEnumeration()

    /** Every walk waits until called with [on] `false` — a library not enumerated yet. */
    suspend fun holdEnumeration(on: Boolean)

    /** Whether a read by identifier can see the library; [on] `false`, every such read answers it cannot. */
    suspend fun byIdReadable(on: Boolean)

    /**
     * The next import is refused before anything is created — or, [afterCreating] `true`, it creates the photo and then
     * reports failure.
     */
    suspend fun failNextImport(afterCreating: Boolean? = null)

    /**
     * The next import parks before its commit lands (the library answers *absent* about it meanwhile) — or, [afterCommit]
     * `true`, after it: the photo exists and the report never comes, the shape a process death leaves. The park is told
     * as [WorldEvent.LibraryImportParked]; [resumeImport] ends it.
     */
    suspend fun suspendNextImport(afterCommit: Boolean? = null)

    /** The parked import's outcome is delivered: it lands unless [succeeded] is `false`. Omitted, it lands. */
    suspend fun resumeImport(succeeded: Boolean? = null)

    /** How many times one import may be made before the library fails it as a live-lock (50 unless set). */
    suspend fun attemptCap(cap: Int)

    // ---- albums -----------------------------------------------------------------------------------

    /** Another app (WhatsApp, say) puts [asset] into its album titled [title]. */
    suspend fun placeIn(title: String, asset: String)

    /** Every add to an album waits until called with [on] `false`. */
    suspend fun holdAdds(on: Boolean)

    /**
     * How this library holds an album: an iPhone's [AlbumKind.COLLECTION] (the default) or an Android phone's
     * [AlbumKind.FOLDER]. The app reads it as it starts, so it takes effect at the next relaunch, as on a device.
     */
    suspend fun albumKind(kind: AlbumKind)

    /** The person deletes the app's album [album]; a folder album goes with the photos in it. */
    suspend fun deleteAlbum(album: String)

    /**
     * The person renames the app's album [album] to [title] in a gallery app. A collection album (an iPhone's) only takes
     * the new title: it is the same album, still the app's. A folder album's photos move to a folder of the new name,
     * and the album the app knows is left empty.
     */
    suspend fun renameAlbum(album: String, title: String)

    // ---- reads ------------------------------------------------------------------------------------

    /** The library as it stands: its photos, the grant, the selection, and how it answers. */
    suspend fun library(): LibraryView

    /** Every album the app made, in the order it made them. */
    suspend fun albums(): List<AppAlbum>

    /** What the app imported, and the import parked now, if any. */
    suspend fun imports(): ImportsView

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<LibraryLevers>(
        LibraryLevers::class,
        "library",
        setOf(LeverNeed.LIBRARY_MOCKED),
        { serviceDescriptorOf() },
    )
}

/**
 * Bytes no photo library can decode — what a download brings (`DownloadSessionLevers.finish`'s bytes) when a member
 * receives a file their phone cannot open. The library mock fails exactly these, and its contract binding holds them
 * equal to the bytes the photo-library contracts stage on every real host.
 */
val NOT_AN_IMAGE: ByteArray = "this is not an image".encodeToByteArray()

/** What a photo the person takes is — each a shape the app's selection policy decides on. */
@Serializable
enum class LibraryPhotoKind {
    /** A camera photo above the image floor. */
    @SerialName("photo")
    PHOTO,

    /** A photo below the image floor — received media. */
    @SerialName("low-res")
    LOW_RES,

    @SerialName("screenshot")
    SCREENSHOT,

    @SerialName("screen-recording")
    SCREEN_RECORDING,

    /** A 1080p video: below the image floor, above the video floor. */
    @SerialName("hd-video")
    HD_VIDEO,

    /** A 1100×900 video: narrower than 1280, but with more pixels than 1280×720, so above the video floor. */
    @SerialName("narrow-video")
    NARROW_VIDEO,

    /** A photo with its paired video. */
    @SerialName("live-photo")
    LIVE_PHOTO,

    @SerialName("gif")
    GIF,
}

/** The app's access to the library, as the person granted it. */
@Serializable
enum class PhotoGrant { NOT_DETERMINED, DENIED, LIMITED, GRANTED }

/** How a library holds an album the app made. */
@Serializable
enum class AlbumKind {
    /** An iPhone's: an album a photo is added to. */
    COLLECTION,

    /** An Android phone's: the folder a photo lives in. */
    FOLDER,
}

/** The library as [LibraryLevers.library] reads it. */
@Serializable
data class LibraryView(
    /** Every photo the library holds, in the order it came. */
    val photos: List<HeldPhoto>,
    val grant: PhotoGrant,
    val requestAnswer: PhotoGrant,
    /** The person's partial selection, by photo id, as they last picked it — `null` until they have. */
    val selection: List<String>?,
    /** Whether the running app's selection observer is open. */
    val observing: Boolean,
    /** How many times the app presented the limited-library picker. */
    val pickersShown: Int,
    val enumerationHeld: Boolean,
    val addsHeld: Boolean,
    val byIdReadable: Boolean,
    val albumKind: AlbumKind,
)

/** A photo the library holds: [imported] when the app saved it (another member's), not the person. */
@Serializable
data class HeldPhoto(val id: String, val date: String?, val imported: Boolean)

/** An album the app made, as [LibraryLevers.albums] reads it. */
@Serializable
data class AppAlbum(
    val id: String,
    val title: String,
    /** The photos it holds now, each once and only while the library holds it — what a person sees in it. */
    val photos: List<String>,
    /** Every photo the app asked to add to it, in order, repeats included. */
    val asked: List<String>,
)

/** What the app imported ([imported]: each import that created a photo, by its source `device/asset`). */
@Serializable
data class ImportsView(val imported: List<String>, val parked: ParkedImport?)

/** An import parked by [LibraryLevers.suspendNextImport]: the photo [asset] of the member device [device]. */
@Serializable
data class ParkedImport(val device: String, val asset: String)
