package app.snapsync.contracts.proxy

import app.snapsync.contracts.CallLog
import app.snapsync.model.AlbumId
import app.snapsync.model.ImportRequest
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryImport
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.SelectionSnapshot
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** [GalleryReader] as its clause's [CallLog] sees it. */
fun GalleryReader.recorded(log: CallLog): GalleryReader = GalleryReaderProxy(this, log)

internal class GalleryReaderProxy(private val inner: GalleryReader, log: CallLog) : GalleryReader {
    private val r = log.recorder("GalleryReader")

    override fun access() = r.answer("access", inner.access())
    override suspend fun assets(filter: AssetFilter) = r.answer("assets", inner.assets(filter))
    override suspend fun assetsById(ids: Set<PlatformAssetId>) = r.answer("assetsById", inner.assetsById(ids))
    override suspend fun resources(ids: Set<PlatformAssetId>) = r.answer("resources", inner.resources(ids))
    override suspend fun albums() = r.answer("albums", inner.albums())
    override suspend fun albumsById(ids: Set<AlbumId>) = r.answer("albumsById", inner.albumsById(ids))
    override suspend fun albumMembers(album: AlbumId, createdFrom: Instant?) =
        r.answer("albumMembers", inner.albumMembers(album, createdFrom))
    override val albumKind get() = r.answer("albumKind", inner.albumKind)
    override suspend fun createAlbum(title: String) = r.returns("createAlbum", inner.createAlbum(title))
    override suspend fun addToAlbum(album: AlbumId, assets: Set<PlatformAssetId>) =
        r.answer("addToAlbum", inner.addToAlbum(album, assets))
    override suspend fun export(
        resource: GalleryResourceHandle,
        to: String,
    ) = r.answer("export", inner.export(resource, to))
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
                onImportPlaceholder = { ref, id ->
                    r.called(
                        "handlers.onImportPlaceholder",
                        Recorder.arg(ref, "AssetRef"),
                        Recorder.arg(id, "PlatformAssetId"),
                    )
                    handlers.onImportPlaceholder(ref, id)
                },
                onImportSettled = { ref, result ->
                    r.called("handlers.onImportSettled", Recorder.arg(ref, "AssetRef"), Recorder.arg(result))
                    handlers.onImportSettled(ref, result)
                },
            ),
        ),
    )

    override val grant: StateFlow<LibraryGrant> = RecordedStateFlow(inner.grant) { r.answer("grant", it) }
    override val selection: StateFlow<SelectionSnapshot?> =
        RecordedStateFlow(inner.selection) { r.returns("selection", it) }

    override fun observeChanges(enabled: Boolean) = r.returns("observeChanges", inner.observeChanges(enabled))
    override suspend fun requestAccess() = r.answer("requestAccess", inner.requestAccess())
    override suspend fun widenSelection() = r.answer("widenSelection", inner.widenSelection())
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
