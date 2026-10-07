package app.snapsync.mock

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.model.FileArea
import app.snapsync.model.SecureSlot
import app.snapsync.model.TransferNetwork
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadTarget
import app.snapsync.ports.DbOpen
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every mock an inspector reads while the app writes it ([tears]): each read is of one whole state — never an
 * exception half-way, never a `null` a copy picked up mid-write, never a write lost to another.
 */
class MockConcurrencyTest {

    private fun assertWhole(torn: Set<String>) = assertEquals(emptySet(), torn, "reads while the app wrote were torn")

    @Test
    fun files_paths_read_while_the_app_stages_and_releases() {
        val disk = FileSystemMock()
        val app = disk.port()
        assertWhole(
            tears(
                write = { round ->
                    val batch = (0 until BATCH).map { "downloads/$round-$it" }
                    batch.forEach { app.write(FileArea.SHARED, it, byteArrayOf(1)) }
                    batch.forEach { app.delete(FileArea.SHARED, it) }
                },
                read = { disk.operator.paths(FileArea.SHARED).whole() },
            ),
        )
    }

    @Test
    fun backend_read_while_devices_join_and_read() {
        val device = MockDevice()
        val backend = device.backend
        val port = backend.port()
        backend.operator.capacity = Int.MAX_VALUE
        val event = backend.operator.registerLegacyEvent("E")
        assertWhole(
            tears(
                write = { round ->
                    runBlocking {
                        port.joinEvent(null, event, "D$round")
                        port.getEvent(null, "absent-$round")
                    }
                },
                read = {
                    backend.operator.unionOf(event)
                    backend.operator.eventReads
                    MockState.encode(device, MockedSystem.BACKEND)
                },
            ),
        )
    }

    @Test
    fun library_read_while_the_app_creates_albums() {
        val device = MockDevice()
        val library = device.library
        val port = library.port()
        assertWhole(
            tears(
                write = { round -> runBlocking { port.createAlbum("A$round") } },
                read = {
                    library.operator.created.whole()
                    MockState.encode(device, MockedSystem.LIBRARY)
                },
            ),
        )
    }

    @Test
    fun library_changes_from_two_threads_are_none_of_them_lost() {
        val library = PhotoLibraryMock()
        val writers = (0 until 2).map { w ->
            thread(name = "writer-$w") { repeat(PER_WRITER) { library.operator.add(LibraryAssets.photo("W$w-$it")) } }
        }
        writers.forEach { it.join() }
        assertEquals(2 * PER_WRITER, library.operator.current().size, "a change landed between another's read and write")
    }

    @Test
    fun upload_queue_read_while_the_app_creates_and_cancels() {
        val device = MockDevice()
        val queue = device.uploadQueue
        val port = queue.port()
        assertWhole(
            tears(
                write = { round ->
                    runBlocking {
                        port.create(UploadSource.Resource(Unit), UploadTarget("https://in-memory/$round", emptyMap(), TransferNetwork.ANY), "k$round")
                        if (round % BATCH == BATCH - 1) port.jobs(UploadJobSet.IN_FLIGHT).forEach { port.cancel(it) }
                    }
                },
                read = {
                    queue.operator.liveJobKeys().whole()
                    queue.operator.created.whole()
                    MockState.encode(device, MockedSystem.UPLOAD_QUEUE)
                },
            ),
        )
    }

    @Test
    fun download_session_read_while_the_app_starts_transfers() {
        val device = MockDevice()
        val downloads = device.downloads
        val port = downloads.port()
        assertWhole(
            tears(
                write = { round -> if (round < CAP) port.start("https://in-memory/$round", "t$round", TransferNetwork.ANY) },
                read = {
                    downloads.operator.inFlight().whole()
                    MockState.encode(device, MockedSystem.DOWNLOADS)
                },
            ),
        )
    }

    @Test
    fun databases_opens_read_while_the_app_opens() {
        val databases = DatabasesMock(refusals = mapOf("db" to DbOpen.Missing))
        val port = databases.port()
        assertWhole(
            tears(
                write = { round -> if (round < CAP) port.open("db", Schema, readOnly = true) },
                read = { databases.operator.opened.whole() },
            ),
        )
    }

    @Test
    fun preferences_read_while_the_app_writes() {
        val device = MockDevice()
        val port = device.preferences.port()
        assertWhole(
            tears(
                write = { round ->
                    (0 until BATCH).forEach { port.set("k$round-$it", "v") }
                    (0 until BATCH).forEach { port.remove("k$round-$it") }
                },
                read = { MockState.encode(device, MockedSystem.PREFERENCES) },
            ),
        )
    }

    @Test
    fun keychain_read_while_the_app_writes() {
        val device = MockDevice()
        val port = device.keychain.port()
        assertWhole(
            tears(
                write = { round ->
                    val slots = (0 until BATCH).map { SecureSlot("s", "a$round-$it", shared = false) }
                    slots.forEach { port.write(it, "v") }
                    slots.forEach { port.delete(it) }
                },
                read = { MockState.encode(device, MockedSystem.KEYCHAIN) },
            ),
        )
    }

    @Test
    fun enclave_read_while_the_app_attests() {
        val device = MockDevice()
        val port = device.enclave.port(available = true)
        assertWhole(
            tears(
                write = { round -> if (round < CAP) runBlocking { port.prove("c$round", null) } },
                read = { MockState.encode(device, MockedSystem.INTEGRITY) },
            ),
        )
    }

    /** A schema no open reaches: every open of `db` is refused before it. */
    private object Schema : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: SqlDriver) = QueryResult.Unit
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) = QueryResult.Unit
    }

    private companion object {
        const val BATCH = 64

        /** How far a write that only grows the state goes — far enough to race, not so far a read crawls. */
        const val CAP = 50_000
        const val PER_WRITER = 2_000
    }
}
