package app.snapsync.android.work

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import app.snapsync.android.gallery.MediaOriginals
import app.snapsync.model.BeforeListen
import app.snapsync.model.ChangeOutcome
import app.snapsync.model.EntryScope
import app.snapsync.model.HandlerSlot
import app.snapsync.model.TransferNetwork
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadError
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadJobState
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadSourceKind
import app.snapsync.model.UploadTarget
import app.snapsync.model.invocation
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import app.snapsync.ports.Upload
import app.snapsync.ports.UploadHandlers
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool

/**
 * The Android app uploader (capability `background-upload`): each job is an HTTP PUT of a photo's original, streamed
 * straight from MediaStore ([UploadSourceKind.RESOURCE] — no staged copy), run in this process.
 *
 * Android has no transfer daemon that finishes an upload for a process that is gone, so:
 * - **While any transfer is live, this adapter holds background time** ([BackgroundTime]): leaving the app does not
 *   freeze them. The hold's expiry cancels every live transfer, reported as a failure, retried on a later run.
 * - **A journal** (the adapter's own preferences) names every job created and not yet reported. A job a killed process
 *   left there — the member revoking access in Settings kills it too — is reported FAILED as soon as the core listens,
 *   so its row is retried rather than waiting on a transfer that no longer exists. A large video stopped mid-way
 *   restarts from the beginning on the next run.
 *
 * **A transfer held to unrestricted networks** ([TransferNetwork.UNRESTRICTED_ONLY], capability `mobile-data`) waits,
 * before it sends a byte, until the default network is unmetered and not withheld — the platform holds nothing here
 * (measured on the API 36 emulator, 2026-10-03: an in-process PUT goes out on cellular and metered Wi-Fi alike), so this
 * adapter plays the part `nsurlsessiond` plays on iOS and the core sees the same created-but-unfinished job either way.
 * A wait the background-time hold outlives ends like any other transfer it stops: reported failed, and re-created by a
 * later run — the upload flow's wake for that run asks for an unmetered network.
 *
 * At most [MAX_LIVE] transfers run at once; the next create answers [UploadCreateOutcome.LIMIT_EXCEEDED]. Every end is
 * reported inline through [UploadHandlers.onFinished], once. There are no platform-held jobs to present: no retry is
 * offered, no terminal job waits for an acknowledgement.
 */
class AndroidUpload(
    context: Context,
    private val time: BackgroundTime,
    private val log: Logger = Logger.withTag("upload"),
    /** How many transfers run at once. A contract binding enters its cap with fewer photos through the same path. */
    private val maxLive: Int = MAX_LIVE,
    /** The journal's preferences file; a binding names its own, so two instances share one to play a relaunch. */
    journalName: String = JOURNAL,
    /**
     * Returns once the phone is on an unmetered network this app may use — what a held transfer waits for
     * ([awaitUnrestrictedNetwork] on a device).
     */
    private val unrestricted: suspend () -> Unit,
) : Upload {

    private val appContext = context.applicationContext
    private val journal = appContext.getSharedPreferences(journalName, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val live = ConcurrentHashMap<String, Transfer>()
    /**
     * A connection per transfer, never a pooled one: a streamed body is sent once, so a pooled connection the server
     * has meanwhile closed fails it outright ("unexpected end of stream" — measured against the loopback fixture) where
     * a fresh connection would have carried it. A photo is megabytes; a handshake each is noise.
     */
    private val client = HttpClient(OkHttp) {
        engine { config { connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)) } }
        install(HttpTimeout) { socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS }
    }
    private var hold: BackgroundTimeHold? = null

    private val handlers = HandlerSlot<UploadHandlers>("Upload", BeforeListen.Dropped)

    override val accepts: UploadSourceKind = UploadSourceKind.RESOURCE

    /** An encrypted event's sealed file is sent from disk, streamed like a library item. */
    override val acceptsFiles: Boolean = true

    /** Register the core's handlers, then report what a previous process left unfinished — each as a failure. */
    override fun listen(handlers: UploadHandlers) {
        this.handlers.set(handlers)
        val orphans = journal.all.mapNotNull { (tag, path) -> (path as? String)?.let { tag to it } }
            .filter { (tag, _) -> !live.containsKey(tag) }
        for ((tag, path) in orphans) {
            log.i { "$tag: its transfer ended with the process that ran it — reported failed, for a retry" }
            finish(tag, job(tag, path, UploadJobState.FAILED, UploadError.Cancelled))
        }
    }

    override suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome =
        log.invocation(EntryScope.None, "upload.create", params = "tag=$tag", result = { "$it" }) { start(source, target, tag) }

    private fun start(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome {
        val body = when (source) {
            is UploadSource.Resource -> (source.handle as? Uri)?.let(Body::MediaItem)
            // A file that is not there is no job: refused now, never a transfer that fails later.
            is UploadSource.File -> File(source.path).takeIf { it.isFile }?.let(Body::LocalFile)
        } ?: return UploadCreateOutcome.FAILED.also { log.w { "$tag: the source is neither a MediaStore item nor a file here" } }
        val path = runCatchingCancellable { URI(target.url).rawPath }.getOrNull()
            ?: return UploadCreateOutcome.FAILED.also { log.w { "$tag: the destination is not a URL" } }
        synchronized(live) {
            if (live.size >= maxLive) return UploadCreateOutcome.LIMIT_EXCEEDED
            journal.edit().putString(tag, path).commit()
            val transfer = Transfer(tag, path)
            live[tag] = transfer
            if (live.size == 1) hold = time.begin("uploads") { expireAll() }
            transfer.job = scope.launch { transfer.run(body, target) }
        }
        return UploadCreateOutcome.CREATED
    }

    override suspend fun jobs(set: UploadJobSet): List<UploadJob> = when (set) {
        UploadJobSet.IN_FLIGHT -> live.values.map { job(it.tag, it.path, UploadJobState.PENDING, null) }
        UploadJobSet.RETRY_OFFERED, UploadJobSet.TERMINAL -> emptyList()
    }

    override suspend fun retry(job: UploadJob, target: UploadTarget): ChangeOutcome =
        ChangeOutcome.Refused(null, "Android offers no retry of its own")

    override suspend fun acknowledge(job: UploadJob): ChangeOutcome = ChangeOutcome.Applied

    override suspend fun cancel(job: UploadJob): ChangeOutcome {
        val transfer = job.tag?.let { live[it] } ?: return ChangeOutcome.Refused(null, "no such transfer")
        transfer.job?.cancel()
        return ChangeOutcome.Applied
    }

    /** The hold's time is up: stop every transfer; each reports its own failure. */
    private fun expireAll() {
        log.i { "background time is up — ${live.size} transfer(s) stopped, to restart on a later run" }
        live.values.forEach { it.job?.cancel() }
    }

    /** Report [tag]'s end once: persisted by the core inline, then dropped from the journal and the live set. */
    private fun finish(tag: String, job: UploadJob) {
        log.invocation(EntryScope.None, "upload.didComplete", params = "tag=$tag state=${job.state}", severity = Severity.Debug) {
            handlers.orNull("$tag's end")?.onFinished?.invoke(job)
        }
        journal.edit().remove(tag).commit()
        synchronized(live) {
            live.remove(tag)
            if (live.isEmpty()) {
                hold?.end()
                hold = null
            }
        }
    }

    private fun job(tag: String, path: String, state: UploadJobState, error: UploadError?) =
        UploadJob(handle = null, tag = tag, destinationPath = path, contentType = null, state = state, error = error, source = null)

    private inner class Transfer(val tag: String, val path: String) {
        var job: Job? = null

        suspend fun run(body: Body, target: UploadTarget) {
            val (state, error) = try {
                if (target.network == TransferNetwork.UNRESTRICTED_ONLY) awaitUnrestricted()
                val status = put(body, target)
                if (status in 200..299) UploadJobState.SUCCEEDED to null else UploadJobState.FAILED to UploadError.Http(status)
            } catch (e: CancellationException) {
                // Stopped — a leave's cancel, or the hold's expiry: reported, then the cancellation goes on.
                report(UploadJobState.CANCELLED, UploadError.Cancelled)
                throw e
            } catch (e: IOException) {
                log.i { "$tag: the transfer broke: ${e::class.simpleName}: ${e.message}" }
                UploadJobState.FAILED to UploadError.Network
            } catch (e: SecurityException) {
                UploadJobState.FAILED to UploadError.Unknown("the photo can no longer be read: ${e.message}")
            } catch (e: IllegalStateException) {
                UploadJobState.FAILED to UploadError.Unknown("${e::class.simpleName}: ${e.message}")
            }
            report(state, error)
        }

        private suspend fun awaitUnrestricted() {
            log.i { "$tag: held for an unmetered network (capability `mobile-data`)" }
            unrestricted()
        }

        private suspend fun report(state: UploadJobState, error: UploadError?) {
            if (state != UploadJobState.SUCCEEDED) log.i { "$tag: $state ($error)" }
            withContext(NonCancellable) { finish(tag, job(tag, path, state, error)) }
        }

        private suspend fun put(body: Body, target: UploadTarget): Int {
            val (open, length) = when (body) {
                is Body.MediaItem -> {
                    val original = MediaOriginals.of(appContext, body.uri)
                    val length = appContext.contentResolver.openAssetFileDescriptor(original, "r")
                        ?.use { it.length.takeIf { size -> size != AssetFileDescriptor.UNKNOWN_LENGTH } }
                    val opener: () -> InputStream = {
                        appContext.contentResolver.openInputStream(original) ?: throw IOException("the media provider opened no stream")
                    }
                    opener to length
                }
                is Body.LocalFile -> ({ body.file.inputStream() } as () -> InputStream) to body.file.length()
            }
            val response = client.put(target.url) {
                // The type rides on the body: Ktor refuses a Content-Type or Content-Length set as a plain header.
                headers {
                    target.headers.filterKeys { !it.equals(CONTENT_TYPE, true) && !it.equals(CONTENT_LENGTH, true) }
                        .forEach { (name, value) -> append(name, value) }
                }
                setBody(Stream(open, length, target.headers.entries.firstOrNull { it.key.equals(CONTENT_TYPE, true) }?.value))
            }
            return response.status.value.also { if (!response.status.isSuccess()) log.w { "$tag: the server answered $it" } }
        }
    }

    /** What a transfer sends: a library item's original, or a file on this device's disk (an encrypted event's). */
    private sealed interface Body {
        class MediaItem(val uri: Uri) : Body
        class LocalFile(val file: File) : Body
    }

    /** The source's bytes as a request body, read as they are sent. */
    private class Stream(private val open: () -> InputStream, length: Long?, type: String?) : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long? = length
        override val contentType: ContentType? = type?.let { runCatchingCancellable { ContentType.parse(it) }.getOrNull() }

        override suspend fun writeTo(channel: ByteWriteChannel) {
            open().use {
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    channel.writeFully(buffer, 0, read)
                }
            }
        }
    }

    private companion object {
        const val JOURNAL = "upload_journal"
        const val MAX_LIVE = 4
        const val CHUNK = 64 * 1024
        const val SOCKET_TIMEOUT_MILLIS = 60_000L
        const val CONTENT_TYPE = "Content-Type"
        const val CONTENT_LENGTH = "Content-Length"
    }
}
