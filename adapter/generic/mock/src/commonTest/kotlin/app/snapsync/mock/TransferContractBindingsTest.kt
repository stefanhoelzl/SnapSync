@file:OptIn(CompositionOnly::class)

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
import app.snapsync.contracts.Relaunch
import app.snapsync.contracts.SessionUnderTest
import app.snapsync.contracts.TransferFixture
import app.snapsync.contracts.TransferSessionContract
import app.snapsync.contracts.TransferSessionState
import app.snapsync.contracts.backgroundOf
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.inboundOf
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.runEntry
import app.snapsync.contracts.uploadClauseKey
import app.snapsync.contracts.uploadTarget
import app.snapsync.contracts.verify
import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.ChangeOutcome
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
    private fun recordingNetwork(received: MutableMap<String, Landed>) = UploadNetwork { url, headers, bytes ->
        val type = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        received[routeOf(url)] = Landed(type, bytes.size.toLong())
        CREATED
    }

    /** What landed at a route: bytes the network received there, under the type the transfer declared. */
    private fun landedOn(received: Map<String, Landed>) = FixtureObjects { path -> received[path] }

    /**
     * The OS's job queue. No queue clause waits on a job it made — what becomes of a made job is the presented states',
     * which this mock settles at once and so never reaches — so the binding plays no network for it.
     */
    private val queue = object : Binding<OsUploadQueueState, QueueUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = UploadQueueMock.Face::class
        override val reaches = setOf(
            OsUploadQueueState.IDLE,
            OsUploadQueueState.AT_CAP,
            OsUploadQueueState.PRESENTED_SUCCEEDED,
            OsUploadQueueState.PRESENTED_REFUSED_ONCE,
            OsUploadQueueState.PRESENTED_RETRY_SPENT,
            OsUploadQueueState.ANY,
        )

        override fun create(state: OsUploadQueueState, clauseId: String, log: CallLog): Entered<QueueUnderTest> {
            // A state this host does not present: another platform's fact, or one no binding here enters.
            if (state !in reaches) return Entered.Unreachable("$state is not a state this host presents")
            val received = mutableMapOf<String, Landed>()
            val mock = UploadQueueMock(answeringNetwork(received), restricted = { false })
            if (state in OsUploadQueueContract.PRESENTED) runEntry { present(mock, state, clauseId) }
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

    /**
     * The OS settles a job the binding made before the clause, as a device's queue presents one after the call that made
     * it — to the route the state's clause looks it up by, answered as that route answers; a spent retry is retried once
     * to the identical destination, as production does, and refused again.
     */
    private suspend fun present(mock: UploadQueueMock, state: OsUploadQueueState, clauseId: String) {
        val target = uploadTarget(base, OsUploadQueueContract.preparedRoute(clauseId, state))
        check(mock.port().create(photo, target) == CreateOutcome.CREATED)
        val name = mock.operator.liveJobs().single()
        mock.operator.completeJob(name)
        if (state == OsUploadQueueState.PRESENTED_RETRY_SPENT) {
            check(mock.port().retry(mock.port().retryOffered().single(), target) == ChangeOutcome.Applied)
            mock.operator.completeJob(name)
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
    private fun answeringNetwork(received: MutableMap<String, Landed>): UploadNetwork {
        val recording = recordingNetwork(received)
        return UploadNetwork { url, headers, bytes ->
            recording.put(url, headers, bytes)
            (TransferFixture.answerOf(routeOf(url)) as? FixtureAnswer.Respond)?.status
        }
    }

    private val session = object : Binding<TransferSessionState, SessionUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = UploadSessionMock.Face::class
        override val reaches = setOf(
            TransferSessionState.IDLE,
            TransferSessionState.AT_CAP,
            TransferSessionState.RESTRICTED_NETWORK,
            TransferSessionState.TAKES_RESOURCES_AND_FILES,
            TransferSessionState.TAKES_FILES,
            TransferSessionState.REPORTS_AS_IT_HAPPENS,
            TransferSessionState.RELAUNCHED_WITH_EVENTS,
        )

        override fun create(state: TransferSessionState, clauseId: String, log: CallLog): Entered<SessionUnderTest> {
            // A state this host does not present: another platform's fact, or one no binding here enters.
            if (state !in reaches) return Entered.Unreachable("$state is not a state this host presents")
            val received = mutableMapOf<String, Landed>()
            var restricted = state == TransferSessionState.RESTRICTED_NETWORK
            val held: (TransferNetwork) -> Boolean = { rule -> restricted && rule == TransferNetwork.UNRESTRICTED_ONLY }
            val resources = state == TransferSessionState.TAKES_RESOURCES_AND_FILES
            val mock = UploadSessionMock(answeringNetwork(received), held, sendsResources = resources)
            // The binding plays the network around the mock's own face, so what the clause sees is the mock's answer.
            val networked = NetworkedSession(mock, mock.port().recorded(log))
            val ended = mutableListOf<Ended>()
            val handedOver = mutableListOf<Completion>()
            var drains = 0
            networked.inbound(
                TransferHandlers(
                    onFinished = inboundOf { end -> ended += Ended(end.tag, end.failure) },
                    onBackgroundEvents = backgroundOf { _, completion -> handedOver += completion },
                    onEventsDrained = inboundOf { drains++ },
                ),
            )
            if (state == TransferSessionState.AT_CAP) {
                // Fill the cap with transfers to routes that never answer, through the bare face: entering the state.
                runEntry {
                    repeat(UploadSessionMock.LIVE_CAP) { n ->
                        val url = base + TransferSessionContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                        val target = UploadTarget(url, emptyMap(), TransferNetwork.ANY)
                        check(
                            mock.port().create(file, target, uploadClauseKey(clauseId, n + 1)) == CreateOutcome.CREATED,
                        )
                    }
                }
            }
            val relaunch = if (state != TransferSessionState.RELAUNCHED_WITH_EVENTS) {
                null
            } else {
                // A transfer the server refuses, created by a process that is gone, ends while the app is not running.
                val tag = TransferSessionContract.relaunchedTag(clauseId)
                val refused = base + TransferSessionContract.path(clauseId, FixtureAnswer.Respond(403))
                runEntry {
                    check(
                        mock.port().create(file, UploadTarget(refused, emptyMap(), TransferNetwork.ANY), tag) == CreateOutcome.CREATED,
                    )
                }
                Relaunch(
                    deliver = {
                        runEntry { mock.operator.complete(tag) }
                        mock.operator.handBack(
                            object : Completion {
                                override fun complete() = Unit
                            },
                        )
                    },
                    handedOver = { handedOver.toList() },
                    drains = { drains },
                )
            }
            return Entered.Ready(
                SessionUnderTest(
                    session = networked,
                    base = base,
                    // The mocked session sends a file, as the app's `URLSession` does — and a resource where it plays Android's.
                    usable = { if (resources) TransferSource.Resource(photo) else file },
                    unusable = {
                        TransferSource.Resource(if (resources) NotThisPlatformsHandle else photo)
                    },
                    ended = { ended.toList() },
                    objects = landedOn(received),
                    liftRestriction = {
                        restricted = false
                        networked.answerHeld()
                    },
                    fileSource = { file },
                    relaunch = relaunch,
                ),
            )
        }
    }

    /** The download session, with the binding answering each started transfer the way its route says. */
    private inner class NetworkedDownload(
        private val mock: DownloadSessionMock,
        private val bodies: MutableMap<String, ByteArray>,
        private val double: Download,
        /** Whether the platform delivers a finish by waking the app — the completion handed over first, the drain last. */
        private val wakes: Boolean = false,
    ) : Download by double {
        private val urls = mutableMapOf<String, String>()

        /** Whether every delivery wake the network handed over was released. */
        var wakesReleased = true
            private set

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
            val outcome = TransferOutcome(
                statusCode = answer.status,
                expectedBytes = if (answer.declaresLength) answer.length.toLong() else -1L,
                receivedBytes = sent.toLong(),
            )
            if (!wakes) return mock.operator.finish(tag, outcome)
            wakesReleased = false
            mock.operator.handBack(
                object : Completion {
                    override fun complete() {
                        wakesReleased = true
                    }
                },
            )
            mock.operator.finish(tag, outcome)
            mock.operator.reportEventsDrained()
        }
    }

    private val download = object : Binding<DownloadState, DownloadUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = DownloadSessionMock.Face::class
        override val reaches = setOf(
            DownloadState.READY,
            DownloadState.RESTRICTED_NETWORK,
            DownloadState.WAKES_TO_DELIVER,
            DownloadState.REFUSES_UNFETCHABLE,
            DownloadState.REFUSES_MALFORMED,
            DownloadState.RELAUNCHED_WITH_EVENTS,
        )

        override fun create(state: DownloadState, clauseId: String, log: CallLog): Entered<DownloadUnderTest> {
            // The temporary files the mock hands over, as the network the binding plays filled them.
            val bodies = mutableMapOf<String, ByteArray>()
            var restricted = state == DownloadState.RESTRICTED_NETWORK
            val opened = mutableListOf<NetworkedDownload>()
            val held: (TransferNetwork) -> Boolean = { rule -> restricted && rule == TransferNetwork.UNRESTRICTED_ONLY }
            val mock = DownloadSessionMock(
                held = held,
                stored = { _, _ -> null },
                refusesUnfetchable = state == DownloadState.REFUSES_UNFETCHABLE,
                refusesMalformed = state == DownloadState.REFUSES_MALFORMED,
            )
            if (state == DownloadState.RELAUNCHED_WITH_EVENTS) {
                // A transfer started by a process that is gone: the OS holds it, and the relaunch delivers its end.
                mock.port().start(
                    base + "/relaunched",
                    emptyMap(),
                    DownloadContract.RELAUNCHED_TAG,
                    TransferNetwork.ANY,
                )
            }
            return Entered.Ready(
                DownloadUnderTest(
                    open = {
                        NetworkedDownload(
                            mock,
                            bodies,
                            mock.port().recorded(log),
                            wakes = state == DownloadState.WAKES_TO_DELIVER,
                        )
                            .also { opened += it }
                    },
                    base = base,
                    readTemp = { bodies[it] },
                    liftRestriction = {
                        restricted = false
                        opened.forEach { it.answerHeld() }
                    },
                    wakeEnded = { opened.all { it.wakesReleased } },
                    relaunch = {
                        mock.operator.handBack(
                            object : Completion {
                                override fun complete() = Unit
                            },
                        )
                        mock.operator.finish(DownloadContract.RELAUNCHED_TAG)
                        mock.operator.reportEventsDrained()
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
