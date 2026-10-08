package app.snapsync.contracts.proxy

import app.snapsync.contracts.CallLog
import app.snapsync.model.AlbumId
import app.snapsync.model.AssetId
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.GalleryAccess
import app.snapsync.model.ImportRequest
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryImport
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.PhotoGrantRead
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/** [GalleryReader] as its clause's [CallLog] sees it. */
fun GalleryReader.recorded(log: CallLog): GalleryReader = GalleryReaderProxy(this, log)

internal class GalleryReaderProxy(private val inner: GalleryReader, log: CallLog) : GalleryReader {
    private val r = log.recorder("GalleryReader")

    // Inherited from [PhotoGrantRead], so counted under it, as the grid counts it.
    override fun access() = r.port("PhotoGrantRead").answer("access", inner.access())
    override suspend fun assets(policy: SelectionPolicy) = r.answer("assets", inner.assets(policy))
    override suspend fun libraryAssets(policy: SelectionPolicy) = r.answer("libraryAssets", inner.libraryAssets(policy))
    override suspend fun assetsById(ids: Set<AssetId>) = r.answer("assetsById", inner.assetsById(ids))
    override suspend fun resources(ids: Set<AssetId>) = r.answer("resources", inner.resources(ids))
    override suspend fun albums() = r.answer("albums", inner.albums())
    override suspend fun albumsById(ids: Set<AlbumId>) = r.answer("albumsById", inner.albumsById(ids))
    override suspend fun albumMembers(album: AlbumId, since: CaptureCutoff?) =
        r.answer("albumMembers", inner.albumMembers(album, since))
    override val albumKind get() = r.answer("albumKind", inner.albumKind)
    override suspend fun createAlbum(title: String) = r.returns("createAlbum", inner.createAlbum(title))
    override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>) =
        r.answer("addToAlbum", inner.addToAlbum(album, assets))
    override suspend fun export(resource: Resource, to: String) = r.answer("export", inner.export(resource, to))
}

/** [LibraryChangeTokenRead] as its clause's [CallLog] sees it. */
fun LibraryChangeTokenRead.recorded(log: CallLog): LibraryChangeTokenRead = LibraryChangeTokenReadProxy(this, log)

internal class LibraryChangeTokenReadProxy(private val inner: LibraryChangeTokenRead, log: CallLog) :
    LibraryChangeTokenRead {
    private val r = log.recorder("LibraryChangeTokenRead")

    override suspend fun changeToken(): LibraryChangeToken? =
        r.returns("changeToken", inner.changeToken())?.let { TokenProxy(it, r.handle("LibraryChangeToken")) }

    /** The handle, recorded; the token it is compared with is unwrapped, since an adapter compares only its own. */
    private class TokenProxy(val inner: LibraryChangeToken, private val r: Recorder) : LibraryChangeToken {
        override fun sameLibraryAs(other: LibraryChangeToken) =
            r.answer("sameLibraryAs", inner.sameLibraryAs((other as? TokenProxy)?.inner ?: other))
    }
}

/** [GalleryImport] as its clause's [CallLog] sees it. */
fun GalleryImport.recorded(log: CallLog): GalleryImport = GalleryImportProxy(this, log)

internal class GalleryImportProxy(private val inner: GalleryImport, log: CallLog) : GalleryImport {
    private val r = log.recorder("GalleryImport")

    override suspend fun import(request: ImportRequest) = r.answer("import", inner.import(request))
}

/**
 * [Gallery] as its clause's [CallLog] sees it. A member it inherits from another port is recorded under THAT port, as
 * the grid counts it — so it delegates to those ports' own proxies.
 */
fun Gallery.recorded(log: CallLog): Gallery = GalleryProxy(this, log)

internal class GalleryProxy(private val inner: Gallery, log: CallLog) :
    Gallery,
    GalleryReader by GalleryReaderProxy(inner, log),
    LibraryChangeTokenRead by LibraryChangeTokenReadProxy(inner, log),
    GalleryImport by GalleryImportProxy(inner, log) {
    private val r = log.recorder("Gallery")

    override fun listen(handlers: GalleryHandlers) = r.returns(
        "listen",
        inner.listen(
            GalleryHandlers(
                onChanged = { snapshot ->
                    r.called("handlers.onChanged", Recorder.arg(snapshot, "SelectionSnapshot"))
                    handlers.onChanged(snapshot)
                },
                onImportPlaceholder = { ref, id ->
                    r.called("handlers.onImportPlaceholder", Recorder.arg(ref, "AssetRef"), Recorder.arg(id, "AssetId"))
                    handlers.onImportPlaceholder(ref, id)
                },
                onImportSettled = { ref, result ->
                    r.called("handlers.onImportSettled", Recorder.arg(ref, "AssetRef"), Recorder.arg(result))
                    handlers.onImportSettled(ref, result)
                },
            ),
        ),
    )

    override fun observeChanges(enabled: Boolean) = r.returns("observeChanges", inner.observeChanges(enabled))
    override suspend fun requestAccess() = r.answer("requestAccess", inner.requestAccess())
    override suspend fun widenSelection() = r.answer("widenSelection", inner.widenSelection())
}

/** [PhotoGrantRead] as its clause's [CallLog] sees it. */
fun PhotoGrantRead.recorded(log: CallLog): PhotoGrantRead = PhotoGrantReadProxy(this, log)

internal class PhotoGrantReadProxy(private val inner: PhotoGrantRead, log: CallLog) : PhotoGrantRead {
    private val r = log.recorder("PhotoGrantRead")

    override fun access() = r.answer("access", inner.access())
}

/** [PhotoAccessStatusSource] as its clause's [CallLog] sees it. */
fun PhotoAccessStatusSource.recorded(log: CallLog): PhotoAccessStatusSource = PhotoAccessStatusSourceProxy(this, log)

internal class PhotoAccessStatusSourceProxy(private val inner: PhotoAccessStatusSource, log: CallLog) :
    PhotoAccessStatusSource {
    private val r = log.recorder("PhotoAccessStatusSource")

    override val permission: StateFlow<GalleryAccess> = RecordedStateFlow(inner.permission) {
        r.answer("permission", it)
    }
}

/**
 * A [StateFlow] whose every reading — a `value` read as much as a collected emission — is recorded: a state's current
 * value is what it answers, however the clause asks.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class RecordedStateFlow<T>(private val inner: StateFlow<T>, private val record: (T) -> Unit) : StateFlow<T> {
    override val value: T get() = inner.value.also(record)
    override val replayCache: List<T> get() = inner.replayCache
    override suspend fun collect(collector: FlowCollector<T>): Nothing = inner.collect {
        record(it)
        collector.emit(it)
    }
}
