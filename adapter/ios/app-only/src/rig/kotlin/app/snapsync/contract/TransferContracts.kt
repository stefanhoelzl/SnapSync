@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.contract

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
import app.snapsync.contracts.Host
import app.snapsync.contracts.Landed
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.RunParameters
import app.snapsync.contracts.StagingDisk
import app.snapsync.contracts.TransferUnderTest
import app.snapsync.download.IosDownloadTransport
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.engine.iosLedgerStore
import app.snapsync.ios.urlsession.IosUrlSessionUploadPlatform
import app.snapsync.model.Resource
import app.snapsync.model.UploadRequest
import app.snapsync.model.assetIdFromUploadKey
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
import kotlinx.coroutines.runBlocking
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
import platform.Foundation.writeToFile
import platform.Photos.PHAsset
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHPhotoLibrary
import platform.posix.memcpy

/**
 * The simulator app's live bindings of the two app-process transfer contracts (capability `port-contracts`, "An
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

class SimAppBackgroundTransferBinding : Binding<BackgroundTransferState, TransferUnderTest>, RunParameters {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(BackgroundTransferState.IDLE, BackgroundTransferState.AT_CAP)

    private val fixture = FixtureAddress()

    override fun accept(params: Map<String, String>): String? = fixture.accept(params)

    override fun create(state: BackgroundTransferState, clauseId: String): Entered<TransferUnderTest> {
        val base = fixture.require()
        val contract = BackgroundTransferContract.name
        // A real SQLDelight ledger over a fresh directory — never the app's own, which lives in the App Group.
        val ledger = iosLedgerStore(scratch("$contract-ledger", clauseId))
        val platform = IosUrlSessionUploadPlatform(
            log = Logger.withTag("contract"),
            appGroup = LEDGER_APP_GROUP,
            sessionIdentifier = "app.snapsync.contract.upload.$clauseId",
            ledger = ledger,
            cap = CAP,
            onTerminal = {},
            onEventsFinished = {},
        )
        val usable: suspend (String) -> Resource = { key ->
            Resource(key, assetIdFromUploadKey(key), "image/jpeg", emptyMap(), seedOne(contract, clauseId))
        }
        if (state == BackgroundTransferState.AT_CAP) {
            runBlocking {
                repeat(CAP) { n ->
                    val resource = usable(BackgroundTransferContract.key(clauseId, n = n + 1))
                    val url = base + BackgroundTransferContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                    val created = platform.createJob(UploadRequest(url, mapOf("Content-Type" to "image/jpeg"), resource), resource)
                    check(created == app.snapsync.ports.CreateResult.CREATED) { "filling the cap: transfer ${n + 1} was $created" }
                }
            }
        }
        return Entered.Ready(
            TransferUnderTest(
                transfer = platform,
                base = base,
                usable = usable,
                unusable = { key -> Resource(key, assetIdFromUploadKey(key), "image/jpeg", emptyMap(), Unit) },
                ledger = ledger,
                objects = fixtureObjects(base),
            ),
            // Held transfers end with their clause, not with the fixture's hold timeout.
            dispose = { runBlocking { platform.cancelTransfers() } },
        )
    }

    private companion object {
        /** Production's own cap is 4; a smaller one enters AT_CAP with fewer seeded photos and the same code path. */
        const val CAP = 2
    }
}

class SimAppDownloadTransportBinding : Binding<DownloadTransportState, DownloadUnderTest>, RunParameters {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(DownloadTransportState.READY)

    private val fixture = FixtureAddress()

    override fun accept(params: Map<String, String>): String? = fixture.accept(params)

    override fun create(state: DownloadTransportState, clauseId: String): Entered<DownloadUnderTest> {
        val disk = object : StagingDisk {
            override fun read(path: String): ByteArray? = NSData.dataWithContentsOfFile(path)?.toByteArray()

            override fun write(path: String, bytes: ByteArray) {
                NSFileManager.defaultManager.createDirectoryAtPath(
                    path.substringBeforeLast('/'),
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
                check(bytes.toNSData().writeToFile(path, atomically = true)) { "could not seed $path" }
            }
        }
        return Entered.Ready(
            DownloadUnderTest(
                open = { host -> IosDownloadTransport(host, Logger.withTag("contract")) },
                base = fixture.require(),
                staging = scratch(DownloadTransportContract.name, clauseId),
                disk = disk,
            ),
        )
    }
}
