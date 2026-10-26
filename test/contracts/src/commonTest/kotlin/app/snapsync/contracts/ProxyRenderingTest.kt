@file:OptIn(CompositionOnly::class)

package app.snapsync.contracts

import app.snapsync.contracts.proxy.recorded
import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Running
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.HttpResult
import app.snapsync.ports.HttpTransport
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.SecureSlot
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import app.snapsync.ports.WriteOutcome
import app.snapsync.wire.api.ChallengeReply
import app.snapsync.wire.api.Routes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * A recording proxy renders a RUNTIME answer exactly as a declaration renders its class literal — the check every clause
 * rests on. Common, so the iOS simulator's run (`iosPlatformTest`) holds Kotlin/Native's class names to it as the JVM's
 * run holds the JVM's; the completeness gate in `:test:architecture` drives every grid cell, but on the JVM only.
 */
class ProxyRenderingTest {

    private fun log() = CallLog().also { it.open() }

    @Test
    fun `a sealed leaf - a generic leaf - an enum entry and null render as declared`() = runTest {
        val log = log()
        val store = object : SecureStore {
            override fun read(slot: SecureSlot) = SecureStoreRead.Absent
            override fun write(slot: SecureSlot, value: String) = WriteOutcome.Ok
            override fun delete(slot: SecureSlot) = WriteOutcome.Ok
        }.recorded(log)
        store.read(SecureSlot("s", "a", shared = false))
        val grant = object : GalleryReader by UnusedReader {
            override fun access() = LibraryGrant.LIMITED
        }.recorded(log)
        grant.access()
        val files = object : Files by UnusedFiles {
            override fun read(area: FileArea, path: String): FileResult<ByteArray> = FileResult.Ok(ByteArray(0))
        }.recorded(log)
        files.read(FileArea.SHARED, "p")
        val backend = HttpTransport {
            HttpResult.Response(200, mapOf("Content-Type" to "application/json"), """{"challenge":"c"}""")
        }
        BackendClient(
            backend,
            "https://backend.test/api/v2",
            ClientIdentity(SERVED_APP_VERSION, token = null),
            log,
        ).send(Routes.Challenge.request())
        object : PlatformDeviceId {
            override fun stableId(): String? = null
        }.recorded(log).stableId()

        val declared = cells {
            on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Absent::class)
            on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.LIMITED)
            on<Files>().answers(Files::read).withGenericLeaf(FileResult.Ok::class)
            backend(Routes.Challenge.ID, ChallengeReply.Issued::class)
            on<PlatformDeviceId>().answers(PlatformDeviceId::stableId).with(null)
        }.cells
        assertEquals(declared.toSet(), log.cells)
    }

    @Test
    fun `a handler call - an enum argument and a handed handle render as declared`() = runTest {
        val log = log()
        lateinit var told: WakeHandlers
        val wake = object : Wake {
            override suspend fun schedule(id: WakeId, trigger: WakeTrigger) = error("unused")
            override fun cancel(id: WakeId) = Unit

            @CompositionOnly
            override fun inbound(handlers: WakeHandlers) {
                told = handlers
            }
        }.recorded(log)
        wake.inbound(WakeHandlers(onWake = grantedOf { _, completion -> completion.complete() }))
        told.onWake.handle(
            WakeId.LibraryChanged,
            object : Completion {
                override fun complete() = Unit
            },
        ).expire()

        val declared = cells {
            on<Wake> {
                answers(Wake::inbound).returns()
                calls(WakeHandlers::onWake, WakeId.LibraryChanged, Completion::class)
                handle<Completion>().answers(Completion::complete).returns()
                handle<Running>().answers(Running::expire).returns()
            }
        }.cells
        assertEquals(declared.toSet(), log.cells)
    }
}

/** Ports whose members the test does not call: Kotlin's delegation needs an instance, so it gets a refusing one. */
private object UnusedFiles : Files {
    override fun read(area: FileArea, path: String) = error("unused")
    override fun readTail(area: FileArea, path: String, maxBytes: Int) = error("unused")
    override fun readRange(area: FileArea, path: String, offset: Long, maxBytes: Int) = error("unused")
    override fun write(area: FileArea, path: String, bytes: ByteArray) = error("unused")
    override fun append(area: FileArea, path: String, bytes: ByteArray) = error("unused")
    override fun delete(area: FileArea, path: String) = error("unused")
    override fun exists(area: FileArea, path: String) = error("unused")
    override fun locate(area: FileArea, path: String) = error("unused")
    override fun move(area: FileArea, from: String, to: String) = error("unused")
    override fun adopt(osPath: String, area: FileArea, to: String) = error("unused")
    override fun list(area: FileArea, directory: String) = error("unused")
}

private object UnusedReader : GalleryReader {
    override fun access() = error("unused")
    override suspend fun assets(filter: AssetFilter) = error("unused")
    override suspend fun assetsById(ids: Set<PlatformAssetId>) = error("unused")
    override suspend fun resources(ids: Set<PlatformAssetId>) = error("unused")
    override suspend fun albums() = error("unused")
    override suspend fun albumsById(ids: Set<PlatformAlbumId>) = error("unused")
    override suspend fun albumMembers(album: PlatformAlbumId, createdFrom: Instant?) = error("unused")
    override val albumKind get() = error("unused")
    override suspend fun createAlbum(title: String) = error("unused")
    override suspend fun addToAlbum(album: PlatformAlbumId, assets: Set<PlatformAssetId>) = error("unused")
    override suspend fun export(resource: GalleryResourceHandle, to: String) = error("unused")
}
