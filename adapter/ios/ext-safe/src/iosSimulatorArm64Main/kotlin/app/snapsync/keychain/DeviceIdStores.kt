@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.keychain

import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * The simulator target's device-id file, in the App-Group container beside the ledger.
 *
 * Deliberately **not** in `RuntimeIdentityTest`'s pinned inventory. That inventory pins literals the
 * OS or the **installed base** holds on its side, so that re-valuing one strands real devices. No
 * installed base holds this: it exists only in a binary that cannot run on a device, and re-valuing
 * it costs one disposable simulator its id. The `.simulator.` infix is there so anyone finding the
 * file in a container can see its scope without reading this.
 */
private const val DEVICE_ID_FILE_NAME: String = "deviceid.simulator.json"

/**
 * The simulator target's binding: the device id in an App-Group **file**, because the addressed
 * Keychain group cannot exist here. Rationale and the measurement are on the `expect` declaration.
 *
 * The App Group is reachable on a simulator **only under an ad-hoc signature** carrying
 * `iosApp/Configuration/simulator.entitlements`; an unsigned build throws from
 * `containerURLForSecurityApplicationGroupIdentifier`. `scripts/sim-sign` applies it. That is the
 * same container the ledger, the download store and the config file already live in, so this
 * introduces no new provisioning requirement — and, being shared with the appex by construction,
 * it is what lets the app and the extension observe one id on this target too.
 */
internal actual fun deviceIdPrimaryStore(): SecureStore = AppGroupFileSecureStore(DEVICE_ID_FILE_NAME)

/**
 * The App-Group container's path, or `null` when this process cannot reach it — which an unsigned
 * build cannot, and an `xctest` host cannot either. Nullable rather than throwing, because the store
 * above turns it into an `Unavailable` read: "I could not look" is not "there is no id", and that
 * distinction is the whole point of the port's three-state read.
 */
private fun appGroupContainerPath(): String? =
    NSFileManager.defaultManager
        .containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)
        ?.path

/**
 * No adoption source on this target: nothing here ever wrote an id anywhere else, so there is
 * nothing an older build could have misplaced.
 *
 * It answers `Absent` rather than being omitted, because `resolveOrMint` distinguishes "nothing is
 * there" from "could not look" on the legacy read too, and treats the latter as disqualifying. Saying
 * `Absent` states the truth; a store that failed here would silently block minting forever.
 */
internal actual fun deviceIdLegacyStore(): SecureStore = NoSuchStore

/** The always-empty store [deviceIdLegacyStore] returns. Writes are refused rather than swallowed. */
private object NoSuchStore : SecureStore {
    override fun read(): SecureStoreRead = SecureStoreRead.Absent
    override fun write(value: String) = error("the simulator target has no legacy device-id store to write")
    override fun migrateProtection() = Unit
    override fun delete() = Unit
}

/**
 * A [SecureStore] over one file in the App-Group container — the simulator target's stand-in for a
 * Keychain item, and **test equipment**: it is confidential only to the extent the container is, and
 * it dies with the install rather than surviving it.
 *
 * Both departures from the port's stated purpose are acceptable *here* and nowhere else. Nothing on a
 * simulator needs protecting from anyone, and reinstall-stability exists so a real device does not
 * orphan its byte partition — a disposable simulator that acquires a fresh id simply enrolls again.
 *
 * The file is written `CompleteUntilFirstUserAuthentication`, matching every other App-Group file, so
 * [StoredProtection.BACKGROUND_READABLE] is the honest answer to a read and no migration is ever
 * requested. Raising it to `Complete` would make the file unreadable while locked, which the
 * entitlements guard forbids for exactly this container.
 */
internal class AppGroupFileSecureStore(
    private val fileName: String,
    /**
     * The directory the file lives in. Defaults to the App-Group container; injectable so tests can
     * point it at a temp directory — the same shape `iosDownloadStore(basePath = …)` already uses,
     * and necessary because an `xctest` host carries no App-Group entitlement and would otherwise
     * only ever exercise the unavailable branch.
     */
    private val directory: () -> String? = ::appGroupContainerPath,
) : SecureStore {

    override fun read(): SecureStoreRead = memScoped {
        val path = filePath()
            ?: return SecureStoreRead.Unavailable(
                "App Group container '$LEDGER_APP_GROUP' unavailable — is the build ad-hoc signed " +
                    "with iosApp/Configuration/simulator.entitlements? (scripts/sim-sign)",
            )
        // Existence is asked before reading rather than inferred from the read's error, so "no id yet"
        // and "the container is unreadable" stay distinct without this store having to classify an
        // NSError domain. The port keeps those apart deliberately: absence may mint, failure may not.
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return SecureStoreRead.Absent
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val data = NSData.dataWithContentsOfFile(path, options = 0u, error = errorVar.ptr)
            ?: return SecureStoreRead.Unavailable(
                errorVar.value?.localizedDescription ?: "read returned no data and no error",
            )
        val text = NSString.create(data, NSUTF8StringEncoding)?.toString()
            ?: return SecureStoreRead.Unavailable("device-id file is not UTF-8")
        // A file that exists but holds nothing is NOT an absence to mint over: something wrote it and
        // produced this, and minting would hand the process a second identity. Unreadable, so the
        // caller defers and a human looks.
        if (text.isBlank()) return SecureStoreRead.Unavailable("device-id file is present but empty")
        SecureStoreRead.Found(text, StoredProtection.BACKGROUND_READABLE)
    }

    override fun write(value: String): Unit = memScoped {
        val path = filePath() ?: error("App Group container '$LEDGER_APP_GROUP' unavailable — cannot persist the device id")
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) as? NSData
            ?: error("device id did not encode as UTF-8")
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val ok = data.writeToFile(
            path,
            options = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication,
            error = errorVar.ptr,
        )
        if (!ok) error("device-id file write failed: ${errorVar.value?.localizedDescription}")
    }

    /** Nothing to migrate: [read] reports the protection this store always writes. */
    override fun migrateProtection() = Unit

    override fun delete(): Unit = memScoped {
        val path = filePath() ?: return
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val ok = NSFileManager.defaultManager.removeItemAtPath(path, error = errorVar.ptr)
        if (!ok) error("device-id file delete failed: ${errorVar.value?.localizedDescription}")
    }

    private fun filePath(): String? = directory()?.let { "$it/$fileName" }
}
