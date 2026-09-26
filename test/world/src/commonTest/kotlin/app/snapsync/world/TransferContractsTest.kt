package app.snapsync.world

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
import app.snapsync.model.TransferOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadError
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadTarget
import app.snapsync.ports.Download
import app.snapsync.ports.Upload
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test

/**
 * The world's two transfer doubles, held to the contracts the app's `URLSession` adapters satisfy (`docs/testing.md`,
 * "The world's transfer doubles are the transfer contracts' Fake bindings").
 *
 * The world has no network, so each binding PLAYS it — the role the loopback fixture server plays for the live
 * bindings. It answers every transfer the way the clause's route says, through the double's own operator actions: an
 * accepting upload is `completeJob`, a refusing one `failJob`, a download `finish` with the outcome the route
 * describes, and a held route is never answered.
 */
class TransferContractsTest {

    private val base = "http://fixture.world"

    /** The route of a world URL — what the fixture server would have been asked for. */
    private fun routeOf(url: String) = url.removePrefix(base)

    /** The world's upload double, with the binding answering each created job the way its route says. */
    private class NetworkedUpload(
        val double: FakeUpload,
        /** The routes the network received a transfer's bytes on — the double's [recordingNetwork]. */
        private val received: Set<String>,
        private val route: (String) -> String,
    ) : Upload by double {
        private val keyAt = mutableMapOf<String, String>()

        override suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome =
            double.create(source, target, tag).also { result ->
                if (result != UploadCreateOutcome.CREATED) return@also
                val path = route(target.url)
                keyAt[path] = tag
                when (val answer = TransferFixture.answerOf(path)) {
                    is FixtureAnswer.Respond ->
                        if (answer.status in 200..299) double.completeJob(tag)
                        else double.failJob(tag, UploadError.Http(answer.status))
                    FixtureAnswer.Hold, null -> Unit
                }
            }

        /** What landed at [path]: bytes the double transferred there, under the type its job was created with. */
        val objects = FixtureObjects { path ->
            val key = keyAt[path] ?: return@FixtureObjects null
            if (path !in received) return@FixtureObjects null
            Landed(double.created.last { it.filename == key }.contentType)
        }
    }

    /**
     * The network the upload double's transfers cross, as the binding plays it: every request reaches it and is
     * accepted, and the route is recorded. The binding completes only jobs whose fixture route accepts.
     */
    private fun recordingNetwork(received: MutableSet<String>) = HttpClient(
        MockEngine { request ->
            received += routeOf(request.url.toString())
            respond("", HttpStatusCode.Created)
        },
    )

    private val upload = object : Binding<UploadState, UploadUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(UploadState.IDLE, UploadState.AT_CAP)

        override fun create(state: UploadState, clauseId: String): Entered<UploadUnderTest> {
            if (state in UploadContract.PRESENTED) {
                return Entered.Unreachable("the world's upload double settles a transfer at once and presents none later")
            }
            val received = mutableSetOf<String>()
            val networked = NetworkedUpload(FakeUpload(recordingNetwork(received)), received, ::routeOf)
            if (state == UploadState.AT_CAP) {
                networked.double.jobLimit = CAP
                // Fill the cap with transfers to routes that never answer.
                runEntry {
                    repeat(CAP) { n ->
                        val key = UploadContract.key(clauseId, n = n + 1)
                        val url = base + UploadContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                        check(networked.create(UploadSource.Resource(Unit), UploadTarget(url, emptyMap()), key) == UploadCreateOutcome.CREATED)
                    }
                }
            }
            return Entered.Ready(
                UploadUnderTest(
                    upload = networked,
                    base = base,
                    // The world's photos carry `Unit` as their platform handle, as a device's carry a `PHAssetResource`.
                    usable = { UploadSource.Resource(Unit) },
                    unusable = { UploadSource.Resource(NotAWorldHandle) },
                    ended = { emptyList<UploadJob>() },
                    objects = networked.objects,
                ),
            )
        }
    }

    /** The world's download double, with the binding answering each started transfer the way its route says. */
    private inner class NetworkedDownload(private val double: FakeDownload, private val bodies: MutableMap<String, ByteArray>) :
        Download by double {
        override fun start(url: String, tag: String): StartResult {
            val started = double.start(url, tag)
            if (started != StartResult.Started) return started
            when (val answer = TransferFixture.answerOf(routeOf(url))) {
                is FixtureAnswer.Respond -> {
                    val sent = if (answer.short) answer.length / 2 else answer.length
                    bodies["temp:/$tag"] = TransferFixture.body(answer.length).copyOf(sent)
                    double.finish(
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
            return started
        }
    }

    private val download = object : Binding<DownloadState, DownloadUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DownloadState.READY)

        override fun create(state: DownloadState, clauseId: String): Entered<DownloadUnderTest> {
            // The temporary files the double hands over, as the network the binding plays filled them.
            val bodies = mutableMapOf<String, ByteArray>()
            return Entered.Ready(
                DownloadUnderTest(open = { NetworkedDownload(FakeDownload(), bodies) }, base = base, readTemp = { bodies[it] }),
            )
        }
    }

    @Test
    fun `the world's upload double satisfies the Upload contract`() = verify(UploadContract, upload)

    @Test
    fun `the world's download double satisfies the Download contract`() = verify(DownloadContract, download)

    /** A payload that is not the world's platform handle — what a device's `PHAssetResource` check refuses. */
    private object NotAWorldHandle

    private companion object {
        const val CAP = 2
    }
}
