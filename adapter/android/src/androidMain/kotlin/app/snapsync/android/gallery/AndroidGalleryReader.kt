package app.snapsync.android.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.snapsync.model.Never
import app.snapsync.model.NeverAnswers
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResource
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import app.snapsync.ports.WriteOutcome
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Instant

/**
 * The Android [GalleryReader] over MediaStore: photos and videos of the member's
 * [DefaultGallery] — `DCIM` and its folders, on every external volume (the `external` collections are the union of
 * them). Trashed and pending items are excluded, as MediaStore's default query excludes them. A partial grant's
 * selection is read whole instead ([selectedItems]): the member's pick is their gallery, `DCIM` or not.
 *
 * - **Identity** is MediaStore's `_ID`: one database spans every external volume, so it is unique across them, it is
 *   owned by the system (it survives this app's reinstall), and a decimal already obeys the canonical asset-id rule, so
 *   it maps by identity.
 * - **Capture date** is `DATE_TAKEN`, to the second, in UTC; null or `0` is no date, which the policy excludes.
 * - **An album is a `DCIM` folder** — MediaStore's bucket, what Android's gallery apps show as albums — titled by the
 *   folder's own name. Unlike iOS, the folder is on every item, so albums read under a partial grant too.
 * - **Android does not say** that a photo is a screenshot, a screen recording, or edited: those facts read `false`
 *   (the screenshot and screen-recording folders are excluded as albums instead).
 *
 * **Its albums are folders** ([albumKind] is [LibraryAlbumKind.FOLDER]; `changes/archive/2026-09-30-android-event-album`
 * D2, D3, D6): a file lives in one folder, so the event album is a folder of its own, `DCIM/SnapSync/<name>/`, and its
 * [PlatformAlbumId] is that path. [createAlbum] picks a free one — numbered when another event, or a folder emptied earlier,
 * holds the name — and creates the directory, so two events never share one. An album resolves ([albumsById]) only
 * while its folder holds an item: an empty folder is no album in any gallery app. [addToAlbum] MOVES a row, keeping its
 * `_ID` (measured, API 30 and 36); the platform lets the app move only the rows it saved itself, and any other is
 * skipped rather than asked about. The event albums sit outside the candidate scope ([DefaultGallery]), so nothing in
 * one is ever read as the member's to share.
 *
 * Reads answer [GalleryRead.NotReadable] without querying unless [access] allows one, and hop to [Dispatchers.IO]:
 * every query is a synchronous binder round-trip into the media provider.
 */
@NeverAnswers(
    Never(member = "albumKind", answer = "LibraryAlbumKind.COLLECTION"),
)
open class AndroidGalleryReader(
    context: Context,
    private val access: () -> LibraryGrant,
    private val log: Logger = Logger.withTag("gallery"),
) : GalleryReader {

    protected val appContext: Context = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    override fun access(): LibraryGrant = access.invoke()

    /** Narrowed by [filter]'s capture range, to the second the platform reports dates in. */
    override suspend fun assets(filter: AssetFilter): GalleryRead<List<GalleryAsset>> =
        run {
            readable { items(narrowingFor(filter)).map { it.asset() } }
        }.also { log.i { "gallery.assets = ${"${(it as? GalleryRead.Read)?.value?.size ?: "not readable"}"}" } }

    override suspend fun assetsById(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAsset>> =
        readable { itemsById(ids).map { it.asset() } }

    override suspend fun resources(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAssetResources>> =
        readable { itemsById(ids).map { it.withResources() } }

    /** The folders the denylist matches — every folder of the default gallery, the event albums' included. */
    override suspend fun albums(): GalleryRead<List<GalleryAlbum>> = readable { buckets(Query()) }

    override suspend fun albumsById(ids: Set<PlatformAlbumId>): GalleryRead<List<GalleryAlbum>> = readable {
        val (folders, others) = ids.map { it.value }.partition { DefaultGallery.isAlbumFolder(it) }
        val wanted = others.mapNotNull { it.toLongOrNull() }
        val buckets = if (wanted.isEmpty()) {
            emptyList()
        } else {
            buckets(
                Query("bucket_id IN (${wanted.joinToString(",")})"),
            )
        }
        buckets + folders.filter { folder -> items(inFolder(folder)).isNotEmpty() }.map {
            GalleryAlbum(PlatformAlbumId(it), folderName(it))
        }
    }

    override suspend fun albumMembers(album: PlatformAlbumId, createdFrom: Instant?): GalleryRead<Set<PlatformAssetId>> =
        membersOf(album.value, createdFrom)

    private suspend fun membersOf(album: String, createdFrom: Instant?): GalleryRead<Set<PlatformAssetId>> = readable {
        val floor = createdFrom?.toEpochMilliseconds()
        val where = if (DefaultGallery.isAlbumFolder(album)) {
            inFolder(album)
        } else {
            val bucket = album.toLongOrNull() ?: return@readable emptySet()
            Query("bucket_id = ?", listOf(bucket.toString()))
        }
        items(
            where.and(floor?.let { Query("datetaken >= ?", listOf(it.toString())) }),
        ).mapTo(mutableSetOf()) { it.assetId }
    }

    override val albumKind: LibraryAlbumKind = LibraryAlbumKind.FOLDER

    override suspend fun createAlbum(title: String): PlatformAlbumId? = withContext(Dispatchers.IO) {
        run {
            // The name is found by querying the media provider, which can refuse: then no album is made.
            try {
                PlatformAlbumId(newAlbum(title))
            } catch (e: SecurityException) {
                null.also { log.w { "createAlbum: the media provider refused — ${e::class.simpleName}: ${e.message}" } }
            } catch (e: SQLiteException) {
                null.also { log.w { "createAlbum: the media provider failed — ${e::class.simpleName}: ${e.message}" } }
            }
        }.also { log.i { "gallery.createAlbum(title=$title) = $it" } }
    }

    private fun newAlbum(title: String): String {
        val name = folderNameFor(title)
        val path = generateSequence(1) { it + 1 }
            .map { n -> "${DefaultGallery.ALBUM_ROOT}${if (n == 1) name else "$name ($n)"}/" }
            .first { path -> !directoryOf(path).exists() && itemsAnywhere(path).isEmpty() }
        // The directory is what takes the name while the album is still empty (measured: it holds, API 30 and 36).
        if (!directoryOf(
                path,
            ).mkdirs()
        ) {
            log.w { "createAlbum: the directory $path was not created; its name is not reserved" }
        }
        return path
    }

    override suspend fun addToAlbum(album: PlatformAlbumId, assets: Set<PlatformAssetId>): WriteOutcome =
        moveInto(album.value, assets)

    private suspend fun moveInto(album: String, assets: Set<PlatformAssetId>): WriteOutcome = withContext(
        Dispatchers.IO,
    ) {
        if (!DefaultGallery.isAlbumFolder(
                album,
            )
        ) {
            return@withContext WriteOutcome.Failed("$album is not an event album's folder")
        }
        val items = try {
            itemsById(assets)
        } catch (e: SecurityException) {
            return@withContext WriteOutcome.Failed("the media provider refused: ${e::class.simpleName}: ${e.message}")
        } catch (e: SQLiteException) {
            return@withContext WriteOutcome.Failed("the media provider failed: ${e::class.simpleName}: ${e.message}")
        }
        var skipped = 0
        items.filter { it.relativePath != album }.forEach { item ->
            val moved = runCatchingCancellable {
                resolver.update(
                    item.uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, album) },
                    null,
                    null,
                )
            }.onFailure { log.i { "addToAlbum: ${item.assetId} is not this app's to move (${it::class.simpleName})" } }
            if (moved.getOrNull() != 1) skipped++
        }
        if (skipped > 0) log.i { "addToAlbum $album: $skipped of ${assets.size} photo(s) left where they are" }
        WriteOutcome.Ok
    }

    /** Copy the item's original bytes — location included where the grant allows — into the file [to]. */
    override suspend fun export(
        resource: GalleryResourceHandle,
        to: String,
    ): WriteOutcome = withContext(Dispatchers.IO) {
        val uri = (resource as? MediaStoreResource)?.uri
            ?: return@withContext WriteOutcome.Failed("$resource is not a media-store resource")
        val target = File(to)
        runCatchingCancellable {
            target.delete()
            val input = resolver.openInputStream(MediaOriginals.of(appContext, uri))
                ?: error("the media provider opened no stream")
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
        }.fold(
            onSuccess = { WriteOutcome.Ok },
            onFailure = {
                target.delete()
                WriteOutcome.Failed("$uri: ${it::class.simpleName}: ${it.message}")
            },
        )
    }

    /**
     * The read on [Dispatchers.IO], or [GalleryRead.NotReadable] — with no query when no grant allows one, and when the
     * media provider refuses the query: the grant revoked while it ran (`SecurityException`; a revocation also ends the
     * process, so this is the window before it does) or the provider's database failing (`SQLiteException`). Either is
     * "could not look", never an empty library.
     */
    protected suspend fun <T> readable(read: () -> T): GalleryRead<T> =
        if (access() !in READABLE) {
            GalleryRead.NotReadable
        } else {
            withContext(Dispatchers.IO) { refusedAsUnreadable("read") { GalleryRead.Read(read()) } }
        }

    /** [block]'s answer, or [GalleryRead.NotReadable] when the media provider refuses it ([readable]). */
    private inline fun <T> refusedAsUnreadable(what: String, block: () -> GalleryRead<T>): GalleryRead<T> = try {
        block()
    } catch (e: SecurityException) {
        log.w { "$what: the media provider refused — ${e::class.simpleName}: ${e.message}" }
        GalleryRead.NotReadable
    } catch (e: SQLiteException) {
        log.w { "$what: the media provider failed — ${e::class.simpleName}: ${e.message}" }
        GalleryRead.NotReadable
    }

    /** Every default-gallery item matching [narrowing], images and videos alike ([DefaultGallery]). */
    internal fun items(narrowing: Query): List<MediaItem> =
        COLLECTIONS.flatMap { collection -> query(collection, Query(DefaultGallery.SQL).and(narrowing)) }

    /**
     * Everything a partial grant lets the app see, wherever on the phone it is stored: the member's selection, plus
     * the rows this app saved itself (its imports, which echo suppression keeps out of the share). Under a partial
     * grant the selection IS the member's gallery, `DCIM` or not — the member picked each photo
     * (`changes/archive/2026-10-15-limited-selection-decides`). Never a read for a full grant — there, an unscoped
     * query is the whole library; [AndroidGallery]'s observer checks the grant around it.
     */
    internal fun selectedItems(): List<MediaItem> = COLLECTIONS.flatMap { collection -> query(collection, Query()) }

    /** The items in the folder [path] exactly — not its subfolders. */
    private fun inFolder(path: String) = Query("relative_path = ?", listOf(path))

    /** Any item at [path], pending and trashed ones included: a name another item holds is taken. */
    private fun itemsAnywhere(path: String): List<Long> {
        val args = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(path))
        }
        return FILES.let { files ->
            resolver.query(files, arrayOf(MediaStore.MediaColumns._ID), args, null)
                ?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
                .orEmpty()
        }
    }

    private fun directoryOf(path: String): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).parentFile, path)

    private fun itemsById(ids: Set<PlatformAssetId>): List<MediaItem> =
        ids.mapNotNull { it.value.toLongOrNull() }.chunked(MAX_IDS_PER_QUERY).flatMap { chunk ->
            items(Query("_id IN (${chunk.joinToString(",")})"))
        }

    private fun buckets(narrowing: Query): List<GalleryAlbum> =
        items(
            narrowing,
        ).mapNotNull { item ->
            item.bucketId?.let {
                GalleryAlbum(
                    PlatformAlbumId(it.toString()),
                    item.bucketName.orEmpty(),
                )
            }
        }
            .distinctBy { it.id }

    private fun query(collection: Uri, scoped: Query): List<MediaItem> {
        return resolver.query(collection, PROJECTION, scoped.selection, scoped.args.toTypedArray(), null)
            ?.use { cursor -> buildList { while (cursor.moveToNext()) add(MediaItem.of(cursor, collection)) } }
            ?: emptyList<MediaItem>().also { log.w { "the media provider answered no cursor for $collection" } }
    }

    /** The native narrowing of [filter]: its capture range, compared to the second as the platform reports dates. */
    private fun narrowingFor(filter: AssetFilter): Query {
        var narrowing = Query()
        filter.createdFrom?.toEpochMilliseconds()?.let {
            // Floored to its second, as the reported date is: an item inside the bound's second is compared by it.
            narrowing = narrowing.and(Query("datetaken >= ?", listOf((it - it.mod(1_000L)).toString())))
        }
        filter.createdUntil?.toEpochMilliseconds()?.let {
            // The date is reported to the second, so an item within the bound's second is kept by it.
            narrowing = narrowing.and(Query("datetaken < ?", listOf((it - it.mod(1_000L) + 1_000).toString())))
        }
        return narrowing
    }

    internal companion object {
        private val FILES: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

        /** The longest folder name an event album gets — far inside every filesystem's limit, and readable in a list. */
        private const val MAX_FOLDER_NAME = 100

        /**
         * [title] as a folder name: without the characters a FAT or exFAT card, or a path, cannot hold, trimmed of the
         * spaces and dots some filesystems drop, and capped; an event whose name leaves nothing is "Event".
         */
        fun folderNameFor(title: String): String =
            title.filterNot { it.isISOControl() || it in "/\\:*?\"<>|" }.trim().trim(
                '.',
            ).trim().take(MAX_FOLDER_NAME).trim()
                .ifEmpty { "Event" }

        /** The folder's own name — what a gallery app titles the album with. */
        fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/')

        val COLLECTIONS: List<Uri> = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        )

        private val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
        )

        /** SQLite's bound-parameter ceiling is far higher; ids are inlined, so this bounds the statement's length. */
        private const val MAX_IDS_PER_QUERY = 500

        /** `DATE_TAKEN` as a capture date, to the second as every capture date the app compares is — or no date. */
        fun capturedAt(dateTaken: Long?): Instant? =
            dateTaken?.takeIf { it != 0L }?.let { Instant.fromEpochSeconds(Math.floorDiv(it, 1_000L)) }
    }
}

/** A selection and its arguments, conjoined with [and]. The empty query matches everything. */
internal data class Query(val selection: String? = null, val args: List<String> = emptyList()) {
    fun and(other: Query?): Query = when {
        other?.selection == null -> this
        selection == null -> other
        else -> Query("($selection) AND (${other.selection})", args + other.args)
    }
}

/** One MediaStore row, as the reader needs it. */
internal class MediaItem(
    val assetId: PlatformAssetId,
    val uri: Uri,
    val isVideo: Boolean,
    private val dateTaken: Long?,
    private val width: Int,
    private val height: Int,
    val relativePath: String?,
    val bucketId: Long?,
    val bucketName: String?,
    private val displayName: String?,
    private val mimeType: String?,
) {
    fun asset(): GalleryAsset = GalleryAsset(
        id = assetId,
        created = AndroidGalleryReader.capturedAt(dateTaken),
        isScreenshot = false,
        isScreenRecording = false,
        isVideo = isVideo,
        isEdited = false,
        // Unknown when MediaStore has no dimensions: never read as tiny.
        pixelArea = if (width > 0 && height > 0) width.toLong() * height else null,
    )

    /** The item's one original — a motion photo is one file — as the upload's resource. */
    fun withResources(): GalleryAssetResources = GalleryAssetResources(
        asset(),
        listOf(
            GalleryResource(
                kind = if (isVideo) ResourceKind.VIDEO else ResourceKind.PHOTO,
                contentType = mimeType ?: "application/octet-stream",
                originalFilename = displayName ?: "$assetId",
                handle = MediaStoreResource(uri),
            ),
        ),
    )

    companion object {
        fun of(cursor: Cursor, collection: Uri): MediaItem {
            val id = cursor.getLong(0)
            return MediaItem(
                assetId = PlatformAssetId(id.toString()),
                uri = ContentUris.withAppendedId(collection, id),
                isVideo = collection == AndroidGalleryReader.COLLECTIONS[1],
                dateTaken = cursor.longOrNull(1),
                width = cursor.getInt(2),
                height = cursor.getInt(3),
                relativePath = cursor.getString(4),
                bucketId = cursor.longOrNull(5),
                bucketName = cursor.getString(6),
                displayName = cursor.getString(7),
                mimeType = cursor.getString(8),
            )
        }

        private fun Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)
    }
}

/** The grants under which a library read answers anything — a full or a partial one. */
private val READABLE = setOf(LibraryGrant.GRANTED, LibraryGrant.LIMITED)

/** A media-store item's original, as the gallery port hands it out: its content URI, which only this adapter reads. */
data class MediaStoreResource(val uri: Uri) : GalleryResourceHandle
