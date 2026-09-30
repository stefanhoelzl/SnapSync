package app.snapsync.android.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumKind
import app.snapsync.model.AlbumRecord
import app.snapsync.model.AssetFacts
import app.snapsync.model.AssetId
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.EntryScope
import app.snapsync.model.GalleryAccess
import app.snapsync.model.GalleryRead
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.Resource
import app.snapsync.model.ResourceRole
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionRule
import app.snapsync.model.WriteOutcome
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.model.invocation
import app.snapsync.ports.GalleryReader
import app.snapsync.model.runCatchingCancellable
import co.touchlab.kermit.Logger
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android [GalleryReader] over MediaStore (capability `photo-sharing`): photos and videos of the member's
 * [DefaultGallery] — `DCIM` and its folders, on every external volume (the `external` collections are the union of
 * them). Trashed and pending items are excluded, as MediaStore's default query excludes them.
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
 * **Its albums are folders** ([albumKind] is [AlbumKind.FOLDER]; capability `event-album`,
 * `changes/archive/2026-09-30-android-event-album`
 * D2, D3, D6): a file lives in one folder, so the event album is a folder of its own, `DCIM/SnapSync/<name>/`, and its
 * [AlbumId] is that path. [createAlbum] picks a free one — numbered when another event, or a folder emptied earlier,
 * holds the name — and creates the directory, so two events never share one. An album resolves ([albumsById]) only
 * while its folder holds an item: an empty folder is no album in any gallery app. [addToAlbum] MOVES a row, keeping its
 * `_ID` (measured, API 30 and 36); the platform lets the app move only the rows it saved itself, and any other is
 * skipped rather than asked about. The event albums sit outside the candidate scope ([DefaultGallery]), so nothing in
 * one is ever read as the member's to share.
 *
 * Reads answer [GalleryRead.NotReadable] without querying unless [access] allows one, and hop to [Dispatchers.IO]:
 * every query is a synchronous binder round-trip into the media provider.
 */
open class AndroidGalleryReader(
    context: Context,
    private val access: () -> GalleryAccess,
    private val log: Logger = Logger.withTag("gallery"),
) : GalleryReader {

    protected val appContext: Context = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    override fun access(): GalleryAccess = access.invoke()

    /** Narrowed by the capture range and the deny-everything rule — the rest falls to the caller's admission. */
    override suspend fun assets(policy: SelectionPolicy): GalleryRead<List<AssetFacts>> =
        log.invocation(EntryScope.None, "gallery.assets", result = { "${(it as? GalleryRead.Read)?.value?.size ?: "not readable"}" }) {
            readable {
                val narrowing = narrowingFor(policy) ?: return@readable emptyList()
                items(narrowing, candidates = true).map { it.facts() }
            }
        }

    override suspend fun assetsById(ids: Set<AssetId>): GalleryRead<List<AssetFacts>> =
        readable { itemsById(ids).map { it.facts() } }

    override suspend fun resources(ids: Set<AssetId>): GalleryRead<List<RawAsset>> =
        readable { itemsById(ids).map { it.rawAsset() } }

    /** The folders the denylist matches — candidates only: an event album is the app's own, never a messenger's. */
    override suspend fun albums(): GalleryRead<List<AlbumRecord>> = readable { buckets(Query(), candidates = true) }

    override suspend fun albumsById(ids: Set<AlbumId>): GalleryRead<List<AlbumRecord>> = readable {
        val (folders, others) = ids.partition { DefaultGallery.isAlbumFolder(it) }
        val wanted = others.mapNotNull { it.toLongOrNull() }
        val buckets = if (wanted.isEmpty()) emptyList() else buckets(Query("bucket_id IN (${wanted.joinToString(",")})"))
        buckets + folders.filter { folder -> items(inFolder(folder)).isNotEmpty() }.map { AlbumRecord(it, folderName(it)) }
    }

    override suspend fun albumMembers(album: AlbumId, since: CaptureCutoff?): GalleryRead<Set<AssetId>> = readable {
        val floor = since?.let { epochMillisOf(it.at.iso) }
        val where = if (DefaultGallery.isAlbumFolder(album)) {
            inFolder(album)
        } else {
            val bucket = album.toLongOrNull() ?: return@readable emptySet()
            Query("bucket_id = ?", listOf(bucket.toString()))
        }
        items(where.and(floor?.let { Query("datetaken >= ?", listOf(it.toString())) })).mapTo(mutableSetOf()) { it.assetId }
    }

    override val albumKind: AlbumKind = AlbumKind.FOLDER

    override suspend fun createAlbum(title: String): AlbumId? = withContext(Dispatchers.IO) {
        val name = folderNameFor(title)
        val path = generateSequence(1) { it + 1 }
            .map { n -> "${DefaultGallery.ALBUM_ROOT}${if (n == 1) name else "$name ($n)"}/" }
            .first { path -> !directoryOf(path).exists() && itemsAnywhere(path).isEmpty() }
        // The directory is what takes the name while the album is still empty (measured: it holds, API 30 and 36).
        if (!directoryOf(path).mkdirs()) log.w { "createAlbum: the directory $path was not created; its name is not reserved" }
        log.i { "createAlbum '$title' → $path" }
        path
    }

    override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>): WriteOutcome = withContext(Dispatchers.IO) {
        if (!DefaultGallery.isAlbumFolder(album)) return@withContext WriteOutcome.Failed("$album is not an event album's folder")
        var skipped = 0
        itemsById(assets).filter { it.relativePath != album }.forEach { item ->
            val moved = runCatchingCancellable {
                resolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, album) }, null, null)
            }.onFailure { log.i { "addToAlbum: ${item.assetId} is not this app's to move (${it::class.simpleName})" } }
            if (moved.getOrNull() != 1) skipped++
        }
        if (skipped > 0) log.i { "addToAlbum $album: $skipped of ${assets.size} photo(s) left where they are" }
        WriteOutcome.Ok
    }

    /** Copy the item's original bytes — location included where the grant allows — into the file [to]. */
    override suspend fun export(resource: Resource, to: String): WriteOutcome = withContext(Dispatchers.IO) {
        val uri = resource.data as? Uri
            ?: return@withContext WriteOutcome.Failed("${resource.filename}: the payload is not a content URI")
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
                WriteOutcome.Failed("${resource.filename}: ${it::class.simpleName}: ${it.message}")
            },
        )
    }

    /** The read on [Dispatchers.IO], or [GalleryRead.NotReadable] with no query when no grant allows one. */
    protected suspend fun <T> readable(read: () -> T): GalleryRead<T> =
        if (!access().grantsPhotoAccess) GalleryRead.NotReadable else GalleryRead.Read(withContext(Dispatchers.IO) { read() })

    /**
     * Every default-gallery item matching [narrowing], images and videos alike — only the [candidates] to share when
     * asked, which leaves out the event albums ([DefaultGallery]).
     */
    internal fun items(narrowing: Query, candidates: Boolean = false): List<MediaItem> =
        COLLECTIONS.flatMap { collection -> query(collection, narrowing, candidates) }

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

    private fun itemsById(ids: Set<AssetId>): List<MediaItem> =
        ids.mapNotNull { it.value.toLongOrNull() }.chunked(MAX_IDS_PER_QUERY).flatMap { chunk ->
            items(Query("_id IN (${chunk.joinToString(",")})"))
        }

    private fun buckets(narrowing: Query, candidates: Boolean = false): List<AlbumRecord> =
        items(narrowing, candidates).mapNotNull { item -> item.bucketId?.let { AlbumRecord(it.toString(), item.bucketName.orEmpty()) } }
            .distinctBy { it.id }

    private fun query(collection: Uri, narrowing: Query, candidates: Boolean): List<MediaItem> {
        val scoped = Query(if (candidates) DefaultGallery.CANDIDATE_SQL else DefaultGallery.SQL).and(narrowing)
        return resolver.query(collection, PROJECTION, scoped.selection, scoped.args.toTypedArray(), null)
            ?.use { cursor -> buildList { while (cursor.moveToNext()) add(MediaItem.of(cursor, collection)) } }
            ?: emptyList<MediaItem>().also { log.w { "the media provider answered no cursor for $collection" } }
    }

    /**
     * The native narrowing [policy] allows — its capture range — or `null` for a policy that admits nothing, which reads
     * nothing at all. A bound that does not parse is left out: the read is then wider, never narrower.
     */
    private fun narrowingFor(policy: SelectionPolicy): Query? {
        var narrowing = Query()
        for (rule in policy.rules) when (rule) {
            SelectionRule.DenyAll -> return null
            is SelectionRule.CaptureAfter -> epochMillisOf(rule.cutoff.at.iso)?.let {
                // Floored to its second, as the reported date is: an item inside the cutoff's second is compared by it.
                narrowing = narrowing.and(Query("datetaken >= ?", listOf((it - it % 1_000).toString())))
            }
            is SelectionRule.CaptureBefore -> epochMillisOf(rule.ceiling.at.iso)?.let {
                // The date is reported to the second, so an item within the ceiling's second is admitted by it.
                narrowing = narrowing.and(Query("datetaken < ?", listOf((it + 1_000).toString())))
            }
            // Not properties MediaStore holds (Android marks no screenshot, no edit) or id sets applied in memory.
            SelectionRule.ExcludeScreenshots, SelectionRule.ExcludeScreenRecordings,
            is SelectionRule.MinImageArea, is SelectionRule.MinVideoArea,
            is SelectionRule.NotEcho, is SelectionRule.NotInDenylistedAlbum -> Unit
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
            title.filterNot { it.isISOControl() || it in "/\\:*?\"<>|" }.trim().trim('.').trim().take(MAX_FOLDER_NAME).trim()
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

        /** An ISO-8601 instant as epoch milliseconds, or `null` when it does not parse. */
        fun epochMillisOf(iso: String): Long? =
            try { Instant.parse(iso).toEpochMilli() } catch (_: DateTimeParseException) { null }

        /** `DATE_TAKEN` as the capture date every platform reports — second precision, UTC — or no date. */
        fun captureDateOf(dateTaken: Long?): String =
            if (dateTaken == null || dateTaken == 0L) "" else Instant.ofEpochMilli(dateTaken).truncatedTo(ChronoUnit.SECONDS).toString()
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
    val assetId: AssetId,
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
    private val creationDate: String = AndroidGalleryReader.captureDateOf(dateTaken)

    fun facts(): AssetFacts = AssetFacts(
        assetId = assetId,
        creationDate = CaptureDate(creationDate),
        isVideo = isVideo,
        // Unknown when MediaStore has no dimensions: admitted on doubt, never read as tiny.
        pixelArea = if (width > 0 && height > 0) width.toLong() * height else null,
    )

    /** The item's one original — a motion photo is one file — as the upload's resource. */
    fun rawAsset(): RawAsset = RawAsset(
        assetId = assetId,
        creationDate = creationDate,
        rawResources = listOf(
            RawResource(
                role = ResourceRole.PRIMARY,
                mimeContentType = mimeType ?: "application/octet-stream",
                originalFilename = displayName ?: "$assetId",
                handle = uri,
            ),
        ),
        facts = facts(),
    )

    companion object {
        fun of(cursor: Cursor, collection: Uri): MediaItem {
            val id = cursor.getLong(0)
            return MediaItem(
                assetId = AssetId(id.toString()),
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
