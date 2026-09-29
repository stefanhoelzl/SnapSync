package app.snapsync.android.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import app.snapsync.model.AlbumId
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
 * The phase-4 writes answer refusals: Android files no photo into an album, so [createAlbum] answers `null` and
 * [addToAlbum] fails.
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
                items(narrowing).map { it.facts() }
            }
        }

    override suspend fun assetsById(ids: Set<AssetId>): GalleryRead<List<AssetFacts>> =
        readable { itemsById(ids).map { it.facts() } }

    override suspend fun resources(ids: Set<AssetId>): GalleryRead<List<RawAsset>> =
        readable { itemsById(ids).map { it.rawAsset() } }

    override suspend fun albums(): GalleryRead<List<AlbumRecord>> = readable { buckets(Query()) }

    override suspend fun albumsById(ids: Set<AlbumId>): GalleryRead<List<AlbumRecord>> = readable {
        val wanted = ids.mapNotNull { it.toLongOrNull() }
        if (wanted.isEmpty()) emptyList() else buckets(Query("bucket_id IN (${wanted.joinToString(",")})"))
    }

    override suspend fun albumMembers(album: AlbumId, since: CaptureCutoff?): GalleryRead<Set<AssetId>> = readable {
        val bucket = album.toLongOrNull() ?: return@readable emptySet()
        val floor = since?.let { epochMillisOf(it.at.iso) }
        val query = Query("bucket_id = ?", listOf(bucket.toString()))
            .and(floor?.let { Query("datetaken >= ?", listOf(it.toString())) })
        items(query).mapTo(mutableSetOf()) { it.assetId }
    }

    override suspend fun createAlbum(title: String): AlbumId? {
        log.i { "createAlbum '$title': Android files no photo into an album" }
        return null
    }

    override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>): WriteOutcome =
        WriteOutcome.Failed("Android files no photo into an album")

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

    /** Every default-gallery item matching [narrowing], images and videos alike. */
    internal fun items(narrowing: Query): List<MediaItem> =
        COLLECTIONS.flatMap { collection -> query(collection, narrowing) }

    private fun itemsById(ids: Set<AssetId>): List<MediaItem> =
        ids.mapNotNull { it.value.toLongOrNull() }.chunked(MAX_IDS_PER_QUERY).flatMap { chunk ->
            items(Query("_id IN (${chunk.joinToString(",")})"))
        }

    private fun buckets(narrowing: Query): List<AlbumRecord> =
        items(narrowing).mapNotNull { item -> item.bucketId?.let { AlbumRecord(it.toString(), item.bucketName.orEmpty()) } }
            .distinctBy { it.id }

    private fun query(collection: Uri, narrowing: Query): List<MediaItem> {
        val scoped = Query(DefaultGallery.SQL).and(narrowing)
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
                bucketId = cursor.longOrNull(5),
                bucketName = cursor.getString(6),
                displayName = cursor.getString(7),
                mimeType = cursor.getString(8),
            )
        }

        private fun Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)
    }
}
