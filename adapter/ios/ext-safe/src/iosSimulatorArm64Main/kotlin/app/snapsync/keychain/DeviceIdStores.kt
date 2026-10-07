@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.keychain

import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureSlots
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.SecureStore
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

/** The simulator: every shared slot in an App-Group file, the rest the Keychain. */
actual fun platformSecureStore(): SecureStore = SimulatorSecureStore(
    keychain = IosSecureStore(),
    files = AppGroupFileSecureStore(
        NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)?.path,
    ),
)

/**
 * Routes every SHARED slot ([SecureSlots.DEVICE_ID], [SecureSlots.EVENT_KEY]) to [files] — the shared access group cannot
 * exist on a simulator — and the rest to [keychain].
 */
internal class SimulatorSecureStore(private val keychain: SecureStore, private val files: SecureStore) : SecureStore {

    private fun storeFor(slot: SecureSlot): SecureStore = if (slot.shared) files else keychain

    override fun read(slot: SecureSlot): SecureStoreRead = storeFor(slot).read(slot)

    override fun write(slot: SecureSlot, value: String): WriteOutcome = storeFor(slot).write(slot, value)

    override fun delete(slot: SecureSlot): WriteOutcome = storeFor(slot).delete(slot)
}

/**
 * A [SecureStore] over files in [directory] (the App-Group container; `null` when this process has none — every
 * read then answers `Unavailable` and every write `Failed`, never absence). One file per slot; the device id keeps
 * the file name simulator builds have always used.
 *
 * The directory is a value resolved once, not a lookup the store calls: no adapter constructor takes a function.
 */
internal class AppGroupFileSecureStore(private val directory: String?) : SecureStore {

    private fun fileName(slot: SecureSlot) =
        if (slot == SecureSlots.DEVICE_ID) DEVICE_ID_FILE_NAME else "${slot.service}.${slot.account}.simulator.json"

    private fun path(slot: SecureSlot): String? = directory?.let { "$it/${fileName(slot)}" }

    private val unavailable = "App Group container '$LEDGER_APP_GROUP' unavailable — is the build ad-hoc signed " +
        "with iosApp/Configuration/simulator.entitlements? (scripts/sim-sign)"

    override fun read(slot: SecureSlot): SecureStoreRead = memScoped {
        val path = path(slot) ?: return SecureStoreRead.Unavailable(unavailable)
        // Existence is asked before reading rather than inferred from the read's error, so "no id yet" and "the
        // container is unreadable" stay distinct: absence may mint, failure may not.
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return SecureStoreRead.Absent
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val data = NSData.dataWithContentsOfFile(path, options = 0u, error = errorVar.ptr)
            ?: return SecureStoreRead.Unavailable(errorVar.value?.localizedDescription ?: "read returned no data and no error")
        val text = NSString.create(data, NSUTF8StringEncoding)?.toString()
            ?: return SecureStoreRead.Unavailable("secure file is not UTF-8")
        // A file that exists but holds nothing is NOT an absence to mint over: something wrote it and produced this,
        // and minting would hand the process a second identity. Unreadable, so the caller defers and a human looks.
        if (text.isBlank()) return SecureStoreRead.Unavailable("secure file is present but empty")
        SecureStoreRead.Found(text, StoredProtection.BACKGROUND_READABLE)
    }

    override fun write(slot: SecureSlot, value: String): WriteOutcome = memScoped {
        val path = path(slot) ?: return WriteOutcome.Failed(unavailable)
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)
            ?: return WriteOutcome.Failed("value did not encode as UTF-8")
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val ok = data.writeToFile(
            path,
            options = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication,
            error = errorVar.ptr,
        )
        if (ok) WriteOutcome.Ok else WriteOutcome.Failed("secure file write failed: ${errorVar.value?.localizedDescription}")
    }

    override fun delete(slot: SecureSlot): WriteOutcome = memScoped {
        val path = path(slot) ?: return WriteOutcome.Failed(unavailable)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return WriteOutcome.Ok
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val ok = NSFileManager.defaultManager.removeItemAtPath(path, error = errorVar.ptr)
        if (ok) WriteOutcome.Ok else WriteOutcome.Failed("secure file delete failed: ${errorVar.value?.localizedDescription}")
    }

    private companion object {
        /** The device-id file every simulator build has used. */
        const val DEVICE_ID_FILE_NAME: String = "deviceid.simulator.json"
    }
}
