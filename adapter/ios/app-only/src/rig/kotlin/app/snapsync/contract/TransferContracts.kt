@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DownloadContract
import app.snapsync.contracts.DownloadState
import app.snapsync.contracts.DownloadUnderTest
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureAnswer
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Host
import app.snapsync.contracts.Landed
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.RunParameters
import app.snapsync.contracts.UploadContract
import app.snapsync.contracts.UploadState
import app.snapsync.contracts.UploadUnderTest
import app.snapsync.contracts.runEntry
import app.snapsync.download.IosDownload
import app.snapsync.gallery.IosGalleryReader
import app.snapsync.ios.urlsession.IosUrlSessionUploadPlatform
import app.snapsync.model.Resource
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadTarget
import app.snapsync.model.WriteOutcome
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.ports.UploadHandlers
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dataWithContentsOfURL
import platform.Photos.PHAsset
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHPhotoLibrary
import platform.posix.memcpy

/**
 * The simulator app's live bindings of the two app-process transfer contracts (`docs/architecture.md`, "An
 * adapter bound per compilation target is real for the clauses it runs there").
 *
 * The adapters run exactly as production builds them. What differs on this target is the ONE lookup
 * `transferSessionConfiguration` resolves — a default session here, a background one on a device — so these runs
 * evidence everything after that lookup (the delegate, staging, the outcome read, the live-task cap, the guarded
 * terminal write) and nothing of the background session's lifecycle, which stays documented in `TransferSessions.kt`.
 *
 * Both exchange bytes with the loopback fixture `scripts/transfer-fixture.py`, whose address the `ios-contracts` job
 * passes as the contract verb's `fixture` parameter ([RunParameters]); a run without it is refused whole.
 */
private class FixtureAddress : RunParameters {
    var base: String? = null
        private set

    override fun accept(params: Map<String, String>): String? {
        base = params["fixture"]?.trimEnd('/')
        return if (base == null) "no fixture: pass ?fixture=http://127.0.0.1:<port> (scripts/transfer-fixture.py)" else null
    }

    fun require(): String = checkNotNull(base) { "the run was not given a fixture" }
}

/** A fresh, empty directory under the process's temporary directory, for one clause of one contract. */
private fun scratch(contract: String, clauseId: String): String {
    val dir = NSTemporaryDirectory() + "contracts/$contract/$clauseId"
    NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    return dir
}

private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { bytes ->
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
}

private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/**
 * What the fixture says landed at a route — read over a foreground fetch of its `/_landed` route, which answers an
 * empty 404 for nothing and `{"contentType": …}` for an object.
 */
private fun fixtureObjects(base: String) = FixtureObjects { path ->
    val data = NSURL.URLWithString("$base/_landed$path")?.let { NSData.dataWithContentsOfURL(it) } ?: return@FixtureObjects null
    if (data.length == 0UL) return@FixtureObjects null
    val json = NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString() ?: return@FixtureObjects null
    Landed(CONTENT_TYPE.find(json)?.groupValues?.get(1))
}

private val CONTENT_TYPE = Regex(""""contentType":\s*"([^"]*)"""")

/** One ordinary photo captured in [clauseId]'s window, as the `PHAssetResource` the upload tier sends. */
private fun seedOne(contract: String, clauseId: String): PHAssetResource = memScoped {
    val date = NSISO8601DateFormatter().dateFromString(PhotoLibrary.window(contract, clauseId).seedDate)
        ?: error("unparseable seed date for $clauseId")
    var localId: String? = null
    val error = alloc<ObjCObjectVar<NSError?>>()
    PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(
        changeBlock = {
            val request = PHAssetCreationRequest.creationRequestForAsset()
            request.addResourceWithType(PHAssetResourceTypePhoto, data = PhotoLibrary.jpeg.toNSData(), options = null)
            request.setCreationDate(date)
            localId = request.placeholderForCreatedAsset?.localIdentifier
        },
        error = error.ptr,
    )
    error.value?.let { error("seeding failed: ${it.domain} ${it.code} ${it.localizedDescription}") }
    val asset = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(checkNotNull(localId)), null).firstObject() as? PHAsset
        ?: error("the seeded photo is not fetchable")
    PHAssetResource.assetResourcesForAsset(asset).firstOrNull() as? PHAssetResource
        ?: error("the seeded photo has no resource")
}

class SimAppUploadBinding : Binding<UploadState, UploadUnderTest>, RunParameters {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(UploadState.IDLE, UploadState.AT_CAP)

    private val fixture = FixtureAddress()

    override fun accept(params: Map<String, String>): String? = fixture.accept(params)

    override fun create(state: UploadState, clauseId: String): Entered<UploadUnderTest> {
        if (state in UploadContract.PRESENTED) return Entered.Unreachable(URL_SESSION_SETTLES_AT_ONCE)
        val base = fixture.require()
        val contract = UploadContract.name
        val platform = IosUrlSessionUploadPlatform(
            log = Logger.withTag("contract"),
            sessionIdentifier = "app.snapsync.contract.upload.$clauseId",
            cap = CAP,
        )
        val ended = mutableListOf<UploadJob>()
        platform.listen(UploadHandlers(onFinished = { ended += it }, onBackgroundEvents = { it.complete() }, onEventsDrained = {}))
        val files = scratch("$contract-files", clauseId)
        // The file uploader sends a file: the seeded photo, exported by the photo library as the upload service does.
        val usable: suspend (String) -> UploadSource = { key ->
            val resource = seedOne(contract, clauseId)
            val path = "$files/$key"
            val exported = IosGalleryReader().export(Resource(key, assetIdFromUploadKey(key), "image/jpeg", emptyMap(), resource), path)
            check(exported == WriteOutcome.Ok) { "exporting the seeded photo for $key failed: $exported" }
            UploadSource.File(path)
        }
        if (state == UploadState.AT_CAP) {
            runEntry {
                repeat(CAP) { n ->
                    val key = UploadContract.key(clauseId, n = n + 1)
                    val url = base + UploadContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                    val created = platform.create(usable(key), UploadTarget(url, mapOf("Content-Type" to "image/jpeg")), key)
                    check(created == UploadCreateOutcome.CREATED) { "filling the cap: transfer ${n + 1} was $created" }
                }
            }
        }
        return Entered.Ready(
            UploadUnderTest(
                upload = platform,
                base = base,
                usable = usable,
                // A photo-library handle, which this uploader does not take: it sends files.
                unusable = { UploadSource.Resource(Unit) },
                ended = { ended.toList() },
                objects = fixtureObjects(base),
            ),
            // Held transfers end with their clause, not with the fixture's hold timeout.
            dispose = { runEntry { platform.jobs(UploadJobSet.IN_FLIGHT).forEach { platform.cancel(it) } } },
        )
    }

    private companion object {
        /** Production's own cap is 4; a smaller one enters AT_CAP with fewer seeded photos and the same code path. */
        const val CAP = 2
    }
}

class SimAppDownloadBinding : Binding<DownloadState, DownloadUnderTest>, RunParameters {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(DownloadState.READY)

    private val fixture = FixtureAddress()

    override fun accept(params: Map<String, String>): String? = fixture.accept(params)

    override fun create(state: DownloadState, clauseId: String): Entered<DownloadUnderTest> = Entered.Ready(
        DownloadUnderTest(
            open = { IosDownload(Logger.withTag("contract")) },
            base = fixture.require(),
            readTemp = { path -> NSData.dataWithContentsOfFile(path)?.toByteArray() },
        ),
    )
}

/** Why the URLSession uploader never reaches [UploadContract.PRESENTED]. */
internal const val URL_SESSION_SETTLES_AT_ONCE =
    "the URLSession uploader reports a transfer the moment it ends and offers no free retry; nothing is presented later"
