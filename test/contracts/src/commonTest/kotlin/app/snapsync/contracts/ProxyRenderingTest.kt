package app.snapsync.contracts

import app.snapsync.contracts.proxy.recorded
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.GalleryAccess
import app.snapsync.model.Reply
import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.AttestStore
import app.snapsync.ports.Backend
import app.snapsync.ports.Completion
import app.snapsync.ports.Files
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.ports.SecureStore
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        val grant = PhotoGrantRead { GalleryAccess.LIMITED }.recorded(log)
        grant.current()
        val files = object : Files by UnusedFiles {
            override fun read(area: FileArea, path: String): FileResult<ByteArray> = FileResult.Ok(ByteArray(0))
        }.recorded(log)
        files.read(FileArea.SHARED, "p")
        val backend = object : Backend by UnusedBackend {
            override suspend fun challenge(): Reply<String> = Reply.Ok("c")
        }.recorded(log)
        backend.challenge()
        val attest = attestStore { null }.recorded(log)
        attest.token()

        val declared = cells {
            on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Absent::class)
            on<PhotoGrantRead>().answers(PhotoGrantRead::current).with(GalleryAccess.LIMITED)
            on<Files>().answers(Files::read).withGenericLeaf(FileResult.Ok::class)
            on<Backend>().answers(Backend::challenge).withGenericLeaf(Reply.Ok::class)
            on<AttestStore>().answers(AttestStore::token).with(null)
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
            override fun listen(handlers: WakeHandlers) {
                told = handlers
            }
        }.recorded(log)
        var expired: (() -> Unit)? = null
        wake.listen(
            WakeHandlers(onWake = { _, completion ->
                completion.onExpired {}
                completion.complete()
            }),
        )
        told.onWake(
            WakeId.LibraryChanged,
            object : Completion {
                override fun complete() = Unit
                override fun onExpired(action: () -> Unit) {
                    expired = action
                }
            },
        )
        expired!!()

        val declared = cells {
            on<Wake> {
                answers(Wake::listen).returns()
                calls(WakeHandlers::onWake, WakeId.LibraryChanged, Completion::class)
                handle<Completion>().answers(Completion::complete).returns()
                handle<Completion>().answers(Completion::onExpired).returns()
                handle<Completion>().callsBack(Completion::onExpired, "action")
            }
        }.cells
        assertEquals(declared.toSet(), log.cells)
    }

    @Test
    fun `only the declared throw is recorded and every throw passes unchanged`() {
        val unavailable = SecureStoreUnavailable("locked")
        val log = log()
        assertEquals(
            unavailable,
            assertFailsWith<SecureStoreUnavailable> {
                attestStore { throw unavailable }.recorded(log).token()
            },
        )
        assertEquals(setOf("AttestStore.token → throws"), log.cells)

        val other = log()
        assertFailsWith<IllegalStateException> {
            attestStore {
                error(
                    "not the declared type",
                )
            }.recorded(other).token()
        }
        assertFailsWith<CancellationException> {
            attestStore {
                throw CancellationException(
                    "x",
                )
            }.recorded(other).token()
        }
        assertEquals(emptySet(), other.cells)
    }

    private fun attestStore(token: () -> String?) = object : AttestStore by UnusedAttestStore {
        override fun token() = token()
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

private object UnusedBackend : Backend {
    override suspend fun challenge() = error("unused")
    override suspend fun mintToken(req: app.snapsync.model.MintRequest) = error("unused")
    override suspend fun renewToken(req: app.snapsync.model.RenewRequest) = error("unused")
    override suspend fun createEvent(token: String?, req: app.snapsync.model.CreateEventRequest) = error("unused")
    override suspend fun getEvent(token: String?, eventId: String) = error("unused")
    override suspend fun renameEvent(token: String?, eventId: String, name: String) = error("unused")
    override suspend fun joinEvent(token: String?, eventId: String, deviceId: String) = error("unused")
    override suspend fun publishManifest(
        token: String?,
        eventId: String,
        deviceId: String,
        manifest: app.snapsync.model.DeviceManifest,
    ) = error("unused")
    override suspend fun leaveEvent(token: String?, eventId: String, deviceId: String, received: Boolean) = error(
        "unused",
    )
    override suspend fun eventFiles(
        token: String?,
        eventId: String,
        cursor: Long?,
        trigger: app.snapsync.model.UnionTrigger,
    ) = error("unused")
    override suspend fun deviceFiles(token: String?, eventId: String, deviceId: String) = error("unused")
    override suspend fun putDeviceConfig(token: String?, deviceId: String, push: app.snapsync.model.PushEndpoint) =
        error("unused")
}

private object UnusedAttestStore : AttestStore {
    override fun token() = error("unused")
    override fun setToken(token: String) = error("unused")
    override fun keyId() = error("unused")
    override fun setKeyId(keyId: String) = error("unused")
    override fun clearToken() = error("unused")
    override fun clearTokenIf(expected: String) = error("unused")
}
