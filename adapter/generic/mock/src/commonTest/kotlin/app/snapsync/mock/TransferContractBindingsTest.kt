package app.snapsync.mock

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.DownloadContract
import app.snapsync.contracts.DownloadState
import app.snapsync.contracts.DownloadUnderTest
import app.snapsync.contracts.Ended
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureAnswer
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Landed
import app.snapsync.contracts.OsUploadQueueContract
import app.snapsync.contracts.OsUploadQueueState
import app.snapsync.contracts.QueueUnderTest
import app.snapsync.contracts.SessionUnderTest
import app.snapsync.contracts.TransferFixture
import app.snapsync.contracts.TransferSessionContract
import app.snapsync.contracts.TransferSessionState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.runEntry
import app.snapsync.contracts.uploadClauseKey
import app.snapsync.contracts.verify
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.Download
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.StartResult
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferOutcome
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadTarget
import kotlin.test.Test

/**
 * The two transfer mocks, held to the contracts the app's `URLSession` adapters satisfy (`docs/testing.md`, "The
 * transfer mocks are the transfer contracts' Fake bindings").
 *
 * A mock has no network, so each binding PLAYS it — the role the loopback fixture server plays for the live bindings.
 * It answers every transfer the way the clause's route says, through the mock's own operator face: an accepting upload
 * is `completeJob`, a refusing one `failJob`, a session transfer `complete` with the route's status, a download `finish` with the outcome the route describes, and a held route
 * is never answered. On a restricted network (capability `mobile-data`) it plays the device's network too: the mocks
 * hold what the network holds, and the binding's lift answers what they held.
 */
class TransferContractBindingsTest {

    private val base = "http://fixture.mock"

    /** The route of a fixture URL — what the fixture server would have been asked for. */
    private fun routeOf(url: String) = url.removePrefix(base)

    /**
     * The network the transfers cross, as the binding plays it: every request reaches it and is accepted, and the route
     * is recorded with the type it declared. The binding settles only transfers whose fixture route accepts.
     */
    private fun recordingNetwork(received: MutableMap<String, String?>) = UploadNetwork { url, headers, _ ->
        received[routeOf(url)] = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        CREATED
    }

    /** What landed at a route: bytes the network received there, under the type the transfer declared. */
    private fun landedOn(received: Map<String, String?>) = FixtureObjects { path ->
        if (path in received) Landed(received[path]) else null
    }

    /**
     * The OS's job queue. No queue clause waits on a job it made — what becomes of a made job is the presented states',
     * which this mock settles at once and so never reaches — so the binding plays no network for it.
     */
    private val queue = object : Binding<OsUploadQueueState, QueueUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(OsUploadQueueState.IDLE, OsUploadQueueState.AT_CAP, OsUploadQueueState.ANY)

        override fun create(state: OsUploadQueueState, clauseId: String, log: CallLog): Entered<QueueUnderTest> {
            // A state this host does not present: another platform's fact, or one no binding here enters.
            if (state !in reaches) return Entered.Unreachable("$state is not a state this host presents")
            val received = mutableMapOf<String, String?>()
            val mock = UploadQueueMock(recordingNetwork(received), restricted = { false })
            if (state == OsUploadQueueState.AT_CAP) {
                mock.operator.jobLimit = CAP
                runEntry {
                    repeat(CAP) { n ->
                        val url = base + OsUploadQueueContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                        check(
                            mock.port().create(photo, UploadTarget(url, emptyMap(), TransferNetwork.ANY)) == CreateOutcome.CREATED,
                        )
                    }
                }
            }
            return Entered.Ready(
                QueueUnderTest(
                    queue = mock.port().recorded(log),
                    base = base,
                    // The mocked photos carry the in-memory library's handle, as a device's carry a PhotoKit resource.
                    usable = { photo },
                    unusable = { NotThisPlatformsHandle },
                    objects = landedOn(received),
                ),
            )
        }
    }

    /** The app's session, with the binding answering each started transfer the way its route says. */
    private inner class NetworkedSession(
        val mock: UploadSessionMock,
        private val double: TransferSession = mock.port(),
    ) :
        TransferSession by double {
        private val made = mutableMapOf<String, String>()

        override suspend fun create(source: TransferSource, target: UploadTarget, tag: String): CreateOutcome =
            double.create(source, target, tag).also {
                if (it == CreateOutcome.CREATED) {
                    made[tag] = target.url
                    answer(tag, target.url)
                }
            }

        private suspend fun answer(tag: String, url: String) {
            when (val answer = TransferFixture.answerOf(routeOf(url))) {
                // The session reports the status the route answers; the network the binding plays carries it.
                is FixtureAnswer.Respond -> mock.operator.complete(tag)
                is FixtureAnswer.Redirect, FixtureAnswer.Hold, null -> Unit
            }
        }

        /** The network allows again: every transfer it held is answered the way its route says. */
        suspend fun answerHeld() = made.forEach { (tag, url) -> answer(tag, url) }
    }

    /** The network a session's transfers cross: what [recordingNetwork] records, answered with the route's status. */
    private fun answeringNetwork(received: MutableMap<String, String?>): UploadNetwork {
        val recording = recordingNetwork(received)
        return UploadNetwork { url, headers, bytes ->
            recording.put(url, headers, bytes)
            (TransferFixture.answerOf(routeOf(url)) as? FixtureAnswer.Respond)?.status
        }
    }

    private val session = object : Binding<TransferSessionState, SessionUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(
            TransferSessionState.IDLE,
            TransferSessionState.AT_CAP,
            TransferSessionState.RESTRICTED_NETWORK,
            TransferSessionState.TAKES_FILES,
            TransferSessionState.REPORTS_AS_IT_HAPPENS,
        )

        override fun create(state: TransferSessionState, clauseId: String, log: CallLog): Entered<SessionUnderTest> {
            // A state this host does not present: another platform's fact, or one no binding here enters.
            if (state !in reaches) return Entered.Unreachable("$state is not a state this host presents")
            val received = mutableMapOf<String, String?>()
            var restricted = state == TransferSessionState.RESTRICTED_NETWORK
            val held: (TransferNetwork) -> Boolean = { rule -> restricted && rule == TransferNetwork.UNRESTRICTED_ONLY }
            val networked = NetworkedSession(UploadSessionMock(answeringNetwork(received), held))
            val recorded = networked.recorded(log)
            val ended = mutableListOf<Ended>()
            recorded.listen(
                TransferHandlers(
                    onFinished = { tag, error -> ended += Ended(tag, error) },
                    onBackgroundEvents = { it.complete() },
                    onEventsDrained = {},
                ),
            )
            if (state == TransferSessionState.AT_CAP) {
                // Fill the cap with transfers to routes that never answer.
                runEntry {
                    repeat(UploadSessionMock.LIVE_CAP) { n ->
                        val url = base + TransferSessionContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                        val target = UploadTarget(url, emptyMap(), TransferNetwork.ANY)
                        check(networked.create(file, target, uploadClauseKey(clauseId, n + 1)) == CreateOutcome.CREATED)
                    }
                }
            }
            return Entered.Ready(
                SessionUnderTest(
                    session = recorded,
                    base = base,
                    // The mocked session sends a file, as the app's `URLSession` does.
                    usable = { file },
                    unusable = { TransferSource.Resource(photo) },
                    ended = { ended.toList() },
                    objects = landedOn(received),
                    liftRestriction = {
                        restricted = false
                        networked.answerHeld()
                    },
                ),
            )
        }
    }

    /** The download session, with the binding answering each started transfer the way its route says. */
    private inner class NetworkedDownload(
        private val mock: DownloadSessionMock,
        private val bodies: MutableMap<String, ByteArray>,
        private val double: Download = mock.port(),
    ) : Download by double {
        private val urls = mutableMapOf<String, String>()

        override fun start(url: String, headers: Map<String, String>, tag: String, network: TransferNetwork): StartResult {
            val started = double.start(url, headers, tag, network)
            if (started != StartResult.Started) return started
            urls[tag] = url
            answer(tag, url)
            return started
        }

        /** The network allows again: every transfer it held is answered the way its route says. */
        fun answerHeld() = urls.forEach { (tag, url) -> answer(tag, url) }

        private fun answer(tag: String, url: String) {
            if (mock.operator.inFlight().none { it.description == tag }) return
            when (val answer = TransferFixture.answerOf(routeOf(url))) {
                // The network the binding plays follows a redirect, as a real transport does: the target answers.
                is FixtureAnswer.Redirect -> respond(tag, answer.to)
                is FixtureAnswer.Respond -> respond(tag, answer)
                FixtureAnswer.Hold, null -> Unit
            }
        }

        private fun respond(tag: String, answer: FixtureAnswer.Respond) {
            val sent = if (answer.short) answer.length / 2 else answer.length
            bodies["temp:/$tag"] = TransferFixture.body(answer.length).copyOf(sent)
            mock.operator.finish(
                tag,
                TransferOutcome(
                    statusCode = answer.status,
                    expectedBytes = if (answer.declaresLength) answer.length.toLong() else -1L,
                    receivedBytes = sent.toLong(),
                ),
            )
        }
    }

    private val download = object : Binding<DownloadState, DownloadUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DownloadState.READY, DownloadState.RESTRICTED_NETWORK)

        override fun create(state: DownloadState, clauseId: String, log: CallLog): Entered<DownloadUnderTest> {
            // A state this host does not present: another platform's fact, or one no binding here enters.
            if (state !in reaches) return Entered.Unreachable("$state is not a state this host presents")
            // The temporary files the mock hands over, as the network the binding plays filled them.
            val bodies = mutableMapOf<String, ByteArray>()
            var restricted = state == DownloadState.RESTRICTED_NETWORK
            val opened = mutableListOf<NetworkedDownload>()
            val held: (TransferNetwork) -> Boolean = { rule -> restricted && rule == TransferNetwork.UNRESTRICTED_ONLY }
            return Entered.Ready(
                DownloadUnderTest(
                    open = {
                        NetworkedDownload(
                            DownloadSessionMock(held = held, stored = { _, _ -> null }),
                            bodies,
                        ).also { opened += it }.recorded(log)
                    },
                    base = base,
                    readTemp = { bodies[it] },
                    liftRestriction = {
                        restricted = false
                        opened.forEach { it.answerHeld() }
                    },
                ),
            )
        }
    }

    @Test
    fun `the upload queue mock satisfies the OsUploadQueue contract`() = verify(OsUploadQueueContract, queue)

    @Test
    fun `the upload session mock satisfies the TransferSession contract`() = verify(TransferSessionContract, session)

    @Test
    fun `the download session mock satisfies the Download contract`() = verify(DownloadContract, download)

    /** A payload that is not this platform's handle — what a device's `PHAssetResource` check refuses. */
    private object NotThisPlatformsHandle : GalleryResourceHandle

    private val photo = InMemoryResource(PlatformAssetId("photo"))
    private val file = TransferSource.File("/mock/upload-staging/photo")

    private companion object {
        const val CAP = 2
        const val CREATED = 201
    }
}
