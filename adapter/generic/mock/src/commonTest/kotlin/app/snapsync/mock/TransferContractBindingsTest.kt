package app.snapsync.mock

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DownloadContract
import app.snapsync.contracts.DownloadState
import app.snapsync.contracts.DownloadUnderTest
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureAnswer
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Landed
import app.snapsync.contracts.TransferFixture
import app.snapsync.contracts.UploadContract
import app.snapsync.contracts.UploadState
import app.snapsync.contracts.UploadUnderTest
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.runEntry
import app.snapsync.contracts.verify
import app.snapsync.model.StartResult
import app.snapsync.model.TransferNetwork
import app.snapsync.model.TransferOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadError
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadTarget
import app.snapsync.ports.Download
import app.snapsync.ports.Upload
import kotlin.test.Test

/**
 * The two transfer mocks, held to the contracts the app's `URLSession` adapters satisfy (`docs/testing.md`, "The
 * transfer mocks are the transfer contracts' Fake bindings").
 *
 * A mock has no network, so each binding PLAYS it — the role the loopback fixture server plays for the live bindings.
 * It answers every transfer the way the clause's route says, through the mock's own operator face: an accepting upload
 * is `completeJob`, a refusing one `failJob`, a download `finish` with the outcome the route describes, and a held route
 * is never answered. On a restricted network (capability `mobile-data`) it plays the device's network too: the mocks
 * hold what the network holds, and the binding's lift answers what they held.
 */
class TransferContractBindingsTest {

    private val base = "http://fixture.mock"

    /** The route of a fixture URL — what the fixture server would have been asked for. */
    private fun routeOf(url: String) = url.removePrefix(base)

    /** The upload queue, with the binding answering each created job the way its route says. */
    private class NetworkedUpload(
        val mock: UploadQueueMock,
        /** The routes the network received a transfer's bytes on — the mock's [recordingNetwork]. */
        private val received: Set<String>,
        private val route: (String) -> String,
    ) : Upload by mock.port() {
        private val double = mock.port()
        private val keyAt = mutableMapOf<String, String>()

        override suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome =
            double.create(source, target, tag).also { result ->
                if (result != UploadCreateOutcome.CREATED) return@also
                val path = route(target.url)
                keyAt[path] = tag
                answer(tag, path)
            }

        private suspend fun answer(tag: String, path: String) {
            when (val answer = TransferFixture.answerOf(path)) {
                is FixtureAnswer.Respond ->
                    if (answer.status in 200..299) mock.operator.completeJob(tag)
                    else mock.operator.failJob(tag, UploadError.Http(answer.status))
                FixtureAnswer.Hold, null -> Unit
            }
        }

        /** The network allows again: every job it held is answered the way its route says. */
        suspend fun answerHeld() = keyAt.forEach { (path, tag) -> answer(tag, path) }

        /** What landed at [path]: bytes the mock transferred there, under the type its job was created with. */
        val objects = FixtureObjects { path ->
            val key = keyAt[path] ?: return@FixtureObjects null
            if (path !in received) return@FixtureObjects null
            Landed(mock.operator.created.last { it.filename == key }.contentType)
        }
    }

    /**
     * The network the queue's transfers cross, as the binding plays it: every request reaches it and is accepted, and
     * the route is recorded. The binding completes only jobs whose fixture route accepts.
     */
    private fun recordingNetwork(received: MutableSet<String>) = UploadNetwork { url, _, _ ->
        received += routeOf(url)
        CREATED
    }

    private val upload = object : Binding<UploadState, UploadUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(UploadState.IDLE, UploadState.AT_CAP, UploadState.RESTRICTED_NETWORK)

        override fun create(state: UploadState, clauseId: String): Entered<UploadUnderTest> {
            if (state in UploadContract.PRESENTED) {
                return Entered.Unreachable("the upload queue mock settles a transfer at once and presents none later")
            }
            val received = mutableSetOf<String>()
            var restricted = state == UploadState.RESTRICTED_NETWORK
            val networked = NetworkedUpload(
                UploadQueueMock(recordingNetwork(received), restricted = { restricted }),
                received,
                ::routeOf,
            )
            if (state == UploadState.AT_CAP) {
                networked.mock.operator.jobLimit = CAP
                // Fill the cap with transfers to routes that never answer.
                runEntry {
                    repeat(CAP) { n ->
                        val key = UploadContract.key(clauseId, n = n + 1)
                        val url = base + UploadContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                        check(networked.create(UploadSource.Resource(Unit), UploadTarget(url, emptyMap(), TransferNetwork.ANY), key) == UploadCreateOutcome.CREATED)
                    }
                }
            }
            return Entered.Ready(
                UploadUnderTest(
                    upload = networked,
                    base = base,
                    // The mocked photos carry `Unit` as their platform handle, as a device's carry a `PHAssetResource`.
                    usable = { UploadSource.Resource(Unit) },
                    unusable = { UploadSource.Resource(NotThisPlatformsHandle) },
                    ended = { emptyList<UploadJob>() },
                    objects = networked.objects,
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

        override fun start(url: String, tag: String, network: TransferNetwork): StartResult {
            val started = double.start(url, tag, network)
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
                is FixtureAnswer.Respond -> {
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
                FixtureAnswer.Hold, null -> Unit
            }
        }
    }

    private val download = object : Binding<DownloadState, DownloadUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DownloadState.READY, DownloadState.RESTRICTED_NETWORK)

        override fun create(state: DownloadState, clauseId: String): Entered<DownloadUnderTest> {
            // The temporary files the mock hands over, as the network the binding plays filled them.
            val bodies = mutableMapOf<String, ByteArray>()
            var restricted = state == DownloadState.RESTRICTED_NETWORK
            val opened = mutableListOf<NetworkedDownload>()
            val held: (TransferNetwork) -> Boolean = { rule -> restricted && rule == TransferNetwork.UNRESTRICTED_ONLY }
            return Entered.Ready(
                DownloadUnderTest(
                    open = { NetworkedDownload(DownloadSessionMock(held = held), bodies).also { opened += it } },
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
    fun `the upload queue mock satisfies the Upload contract`() = verify(UploadContract, upload)

    @Test
    fun `the download session mock satisfies the Download contract`() = verify(DownloadContract, download)

    /** A payload that is not this platform's handle — what a device's `PHAssetResource` check refuses. */
    private object NotThisPlatformsHandle

    private companion object {
        const val CAP = 2
        const val CREATED = 201
    }
}
