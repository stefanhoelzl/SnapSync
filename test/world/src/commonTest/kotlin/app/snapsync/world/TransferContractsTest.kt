package app.snapsync.world

import app.snapsync.contracts.BackgroundTransferContract
import app.snapsync.contracts.BackgroundTransferState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DownloadTransportContract
import app.snapsync.contracts.DownloadTransportState
import app.snapsync.contracts.DownloadUnderTest
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureAnswer
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Landed
import app.snapsync.contracts.StagingDisk
import app.snapsync.contracts.TransferFixture
import app.snapsync.contracts.TransferUnderTest
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.fake.inMemoryLedgerStore
import app.snapsync.model.Resource
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.CreateResult
import app.snapsync.ports.DownloadTask
import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.ports.TransferOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The world's two transfer doubles, held to the contracts the app's `URLSession` adapters satisfy (capability
 * `harness-world-model`, "The world's transfer doubles are the transfer contracts' Fake bindings").
 *
 * The world has no network, so each binding PLAYS it — the role the loopback fixture server plays for the live
 * bindings. It answers every transfer the way the clause's route says, through the double's own operator actions:
 * an accepting upload is `completeJob`, a refusing one `failJob`, a download `finish` with the outcome the route
 * describes, and a held route is never answered. Every outcome a clause reads is read back from the double's own
 * state: the objects it deposited, the paths it staged.
 */
class TransferContractsTest {

    private val base = "http://fixture.world"

    /** The route of a world URL — what the fixture server would have been asked for. */
    private fun routeOf(url: String) = url.removePrefix(base)

    /** A resource the world's double can upload: the world's photos carry `Unit` as their platform handle. */
    private fun worldResource(key: String, data: Any = Unit) =
        Resource(key, assetIdFromUploadKey(key), "image/heic", emptyMap(), data)

    /** The world's upload double, with the binding answering each created job the way its route says. */
    private class NetworkedTransfer(
        val double: FakeBackgroundTransfer,
        private val store: BackendStore,
        private val route: (String) -> String,
    ) : BackgroundTransfer by double {
        private val keyAt = mutableMapOf<String, String>()

        override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult =
            double.createJob(request, resource).also { result ->
                if (result != CreateResult.CREATED) return@also
                val path = route(request.url)
                keyAt[path] = resource.filename
                when (val answer = TransferFixture.answerOf(path)) {
                    is FixtureAnswer.Respond ->
                        if (answer.status in 200..299) double.completeJob(resource.filename)
                        else double.failJob(resource.filename, UploadError.Http(answer.status))
                    FixtureAnswer.Hold, null -> Unit
                }
            }

        /** What landed at [path]: an object the double deposited, under the type its job was created with. */
        val objects = FixtureObjects { path ->
            val key = keyAt[path] ?: return@FixtureObjects null
            if (key !in store.objectsOf(OWN_DEVICE)) return@FixtureObjects null
            Landed(double.created.last { it.filename == key }.contentType)
        }
    }

    private val upload = object : Binding<BackgroundTransferState, TransferUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(BackgroundTransferState.IDLE, BackgroundTransferState.AT_CAP)

        override fun create(state: BackgroundTransferState, clauseId: String): Entered<TransferUnderTest> {
            val store = BackendStore()
            val ledger = inMemoryLedgerStore()
            val networked = NetworkedTransfer(FakeBackgroundTransfer(store, OWN_DEVICE, ledger), store, ::routeOf)
            if (state == BackgroundTransferState.AT_CAP) {
                networked.double.jobLimit = CAP
                // Fill the cap with transfers to routes that never answer. `create` is not a coroutine, and the
                // runner enters the state before the clause's own `runTest` — so the entry gets one of its own.
                runTest {
                    repeat(CAP) { n ->
                        val key = BackgroundTransferContract.key(clauseId, n = n + 1)
                        val url = base + BackgroundTransferContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                        val resource = worldResource(key)
                        check(networked.createJob(UploadRequest(url, emptyMap(), resource), resource) == CreateResult.CREATED)
                    }
                }
            }
            return Entered.Ready(
                TransferUnderTest(
                    transfer = networked,
                    base = base,
                    usable = { key -> worldResource(key) },
                    unusable = { key -> worldResource(key, data = NotAWorldHandle) },
                    ledger = ledger,
                    objects = networked.objects,
                ),
            )
        }
    }

    /** The world's download double, with the binding answering each started transfer the way its route says. */
    private inner class NetworkedDownloads(private val disk: WorldDisk) : (DownloadTransportHost) -> DownloadTransport {
        override fun invoke(host: DownloadTransportHost): DownloadTransport {
            val double = FakeDownloadTransport(disk.tracking(host), disk.paths)
            return object : DownloadTransport {
                override fun start(url: String, description: String): DownloadTask? {
                    val task = double.start(url, description) ?: return null
                    when (val answer = TransferFixture.answerOf(routeOf(url))) {
                        is FixtureAnswer.Respond -> {
                            disk.inFlight[description] = TransferFixture.body(answer.length)
                            double.finish(
                                description,
                                TransferOutcome(
                                    statusCode = answer.status,
                                    expectedBytes = if (answer.declaresLength) answer.length.toLong() else -1L,
                                    receivedBytes = answer.length.toLong(),
                                ),
                            )
                        }
                        FixtureAnswer.Hold, null -> Unit
                    }
                    return task
                }
            }
        }
    }

    /**
     * The world's staging disk as a clause reads it. The double's disk is the SET of staged paths — "staging a
     * transfer puts its destination here, exactly as the real transport's move puts bytes on disk" — so a path's
     * bytes are those of the transfer that last staged it, which this learns by watching the host be told.
     */
    private class WorldDisk : StagingDisk {
        val paths = mutableSetOf<String>()
        val inFlight = mutableMapOf<String, ByteArray>()
        private val bytes = mutableMapOf<String, ByteArray>()

        override fun read(path: String): ByteArray? = if (path in paths) bytes[path] else null

        override fun write(path: String, bytes: ByteArray) {
            paths += path
            this.bytes[path] = bytes
        }

        fun tracking(host: DownloadTransportHost) = object : DownloadTransportHost by host {
            override fun onStaged(description: String, stagedPath: String) {
                inFlight[description]?.let { bytes[stagedPath] = it }
                host.onStaged(description, stagedPath)
            }
        }
    }

    private val download = object : Binding<DownloadTransportState, DownloadUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DownloadTransportState.READY)

        override fun create(state: DownloadTransportState, clauseId: String): Entered<DownloadUnderTest> {
            val disk = WorldDisk()
            return Entered.Ready(DownloadUnderTest(NetworkedDownloads(disk), base, "/staging", disk))
        }
    }

    @Test
    fun `the world's upload double satisfies the BackgroundTransfer contract`() =
        verify(BackgroundTransferContract, upload)

    @Test
    fun `the world's download double satisfies the DownloadTransport contract`() =
        verify(DownloadTransportContract, download)

    /** A payload that is not the world's platform handle — what a device's `PHAssetResource` check refuses. */
    private object NotAWorldHandle

    private companion object {
        const val OWN_DEVICE = "contract-own-device"
        const val CAP = 2
    }
}
