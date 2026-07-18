@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.config

import app.snapsync.model.EventConfig
import app.snapsync.model.encodeConfigFile
import app.snapsync.model.isConfigFileAbsence
import app.snapsync.ports.ConfigFileRead
import app.snapsync.ports.ConfigRead
import app.snapsync.ports.ConfigReader
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import app.snapsync.ports.configReadViaFile

import app.snapsync.engine.LEDGER_APP_GROUP
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * The config file in the App-Group container root (a runtime-identity pin, capability
 * `architecture-guards`): the **storage of record** for the persisted [EventConfig] since migration
 * step 11a. Both processes read and (via migration) write it; the path derives from the same
 * [LEDGER_APP_GROUP] container every other shared store uses.
 */
private const val CONFIG_FILE_NAME: String = "eventconfig.json"

/**
 * The iOS [ConfigSource]/[ConfigStore]/[ConfigReader] since migration step 11a: persists the
 * serialized [EventConfig] in a **versioned-envelope file in the App-Group container**
 * ([CONFIG_FILE_NAME]), with the legacy Keychain item ([KeychainConfigStore]) kept as a
 * **written-through copy** — every save/clear still updates it — so a revert build (which only
 * reads the Keychain) finds a live config for the whole soak window. Deleting the Keychain copy is
 * a separate, later migration step (13b+); **only then** does "reinstall = left the event" become
 * true, because until then a reinstall's surviving Keychain item is indistinguishable from an
 * update-in-place and is deliberately resurrected (see [read]).
 *
 * **The migration lives here, inside the adapter** — not in app startup — because it must run in
 * *whichever process reads first*: the OS can schedule the upload extension before the user ever
 * opens the updated app, and an absent config reads as a definitive not-joined, which clears the
 * joined-marker (a false leave on every joined device). App and extension update atomically, so
 * both carry this adapter; the first read migrates (capability `event-rejoin-reconciliation`).
 *
 * All decode/decision intelligence is pure and `commonTest`-covered (`configReadViaFile` in
 * `ports/`, the envelope codec + absence classifier in `model/`); this class only performs the
 * file IO and maps its `NSError`s onto [ConfigFileRead]. Writes are **atomic** ([NSDataWritingAtomic]:
 * temp file + rename) under [NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication] —
 * the same protection class as the ledger and download DBs, readable while locked after first
 * unlock, which the OS-scheduled (usually-locked) extension cycle requires.
 *
 * Seeds [config] synchronously at construction, exactly like the Keychain store it replaces: a
 * background launch before first unlock seeds `null` (the CUFUA read fails permission-class →
 * unreadable, never absent) and the app shell's unlock hook calls [reload] to repair it.
 * **Readers that act on the absence of a config must use [read], not [config]** — see [ConfigRead].
 */
class FileBackedConfigStore(
    private val keychainStore: KeychainConfigStore = KeychainConfigStore(),
    private val log: Logger = Logger.withTag("fileConfig"),
) : ConfigSource, ConfigStore, ConfigReader {

    private val state = MutableStateFlow(read().joinedOrNull())
    override val config: StateFlow<EventConfig?> = state

    override suspend fun save(config: EventConfig) {
        // Deliberately NO equal-config early return here: a torn save (file written, Keychain
        // crash) followed by a relaunch seeds [state] from the file, and an outer guard would then
        // skip exactly the re-save that repairs the stale Keychain copy. The inner
        // [KeychainConfigStore.save] carries its own idempotence (its state seeds from the
        // Keychain, so a genuinely-stale copy is rewritten and an equal one is a no-op), and the
        // [StateFlow] conflates equal values, so the port's no-redundant-emission contract holds.
        //
        // File first — the storage of record this build reads — then the Keychain write-through
        // (the revert build's copy). A crash between the two leaves the file authoritative and the
        // Keychain copy stale until the next save (equal or not) rewrites it; the file's presence
        // means the stale copy is never consulted by this build (fallback runs only on MISSING).
        writeFile(encodeConfigFile(config))
        keychainStore.save(config)
        state.value = config
    }

    override suspend fun clear() {
        // Keychain FIRST, file second — the reverse of save, deliberately: were the file deleted
        // first, a crash before the Keychain delete would leave exactly the state [read]'s
        // migration fallback resurrects (file missing + Keychain item present), silently undoing
        // the leave. Cleared this way, a crash between leaves the file present: THIS build stays
        // joined and the leave simply retries — while a REVERT build (which reads only the
        // Keychain) already reads left. That divergence is the accepted cost of the ordering; the
        // resurrected-leave alternative is worse because nothing would ever surface it. Both
        // halves are idempotent.
        keychainStore.clear()
        deleteFile()
        state.value = null
    }

    /**
     * The three-state read (capability `event-link`): the pure `configReadViaFile` over this
     * process's file IO, falling back to the written-through Keychain copy **only** on a
     * definitively missing file — and migrating a found copy into the file so the next read
     * answers from the file alone. See the class doc for why a reinstall (file lost, Keychain
     * surviving) is resurrected rather than read as a leave while the write-through lasts.
     */
    override fun read(): ConfigRead {
        val read = configReadViaFile(
            file = readFileRaw(),
            fallback = { keychainStore.read() },
            migrate = { cfg ->
                runCatching { writeFile(encodeConfigFile(cfg)) }
                    .onSuccess { log.i { "config migrated: Keychain → App-Group file" } }
                    .onFailure { log.w(it) { "config migration write failed — the next read retries" } }
            },
            // Compare-and-repair (the pure algorithm re-reads the Keychain after the migrate): a
            // concurrent save/clear in the other process superseded what was just migrated, so the
            // file holds a stale clobber. Best-effort, like the migrate itself.
            repair = { fresh ->
                runCatching {
                    when (fresh) {
                        is ConfigRead.Joined -> writeFile(encodeConfigFile(fresh.config))
                        else -> deleteFile()
                    }
                }.onFailure { log.w(it) { "config migrate repair failed — the next read retries" } }
                log.w { "config migrate raced a concurrent write — repaired to the fresh state" }
            },
        )
        if (read is ConfigRead.Unavailable) {
            log.w { "config file unreadable (status=${read.status}) — NOT 'no config'; caller must defer" }
        }
        return read
    }

    /**
     * Re-read the file into [config] — same contract as the Keychain store's reload: cross-process
     * writers do not notify this process's [StateFlow], and a pre-first-unlock construction seeded
     * `null`; the app calls this from the protected-data unlock hook.
     */
    fun reload() {
        state.value = read().joinedOrNull()
    }

    /** `null` for both *absent* and *unreadable* — acceptable for the UI-facing [config], never for the reconciler. */
    private fun ConfigRead.joinedOrNull(): EventConfig? = (this as? ConfigRead.Joined)?.config

    // ---- file IO (wiring-only; every decision above is in the pure, commonTest-covered layer) ----

    private fun configFilePath(): String? = NSFileManager.defaultManager
        .containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)
        ?.path
        ?.let { "$it/$CONFIG_FILE_NAME" }

    private fun readFileRaw(): ConfigFileRead = memScoped {
        // A missing container is a provisioning/entitlement failure, not evidence about membership:
        // unreadable (defer), never absent — the same posture as any other unknown failure.
        val path = configFilePath()
            ?: return ConfigFileRead.Failed(status = 0, detail = "App Group container '$LEDGER_APP_GROUP' unavailable")
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val data = NSData.dataWithContentsOfFile(path, options = 0u, error = errorVar.ptr)
        if (data != null) {
            val text = NSString.create(data, NSUTF8StringEncoding)?.toString()
                ?: return ConfigFileRead.Failed(status = 0, detail = "config file is not UTF-8")
            return ConfigFileRead.Content(text)
        }
        val failure = errorVar.value
        when {
            failure != null && isConfigFileAbsence(failure.domain, failure.code) -> ConfigFileRead.Missing
            else -> ConfigFileRead.Failed(
                status = (failure?.code ?: 0L).toInt(),
                detail = failure?.localizedDescription ?: "read returned no data and no error",
            )
        }
    }

    private fun writeFile(text: String): Unit = memScoped {
        val path = configFilePath()
            ?: error("App Group container '$LEDGER_APP_GROUP' unavailable — cannot persist config")
        val data = (text as NSString).dataUsingEncoding(NSUTF8StringEncoding) as? NSData
            ?: error("config file content did not encode as UTF-8")
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val ok = data.writeToFile(
            path,
            options = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication,
            error = errorVar.ptr,
        )
        if (!ok) error("config file write failed: ${errorVar.value?.localizedDescription}")
    }

    private fun deleteFile(): Unit = memScoped {
        val path = configFilePath() ?: return
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val ok = NSFileManager.defaultManager.removeItemAtPath(path, error = errorVar.ptr)
        if (!ok) {
            val failure = errorVar.value
            // Deleting an absent file is success (the leave path tolerates it) — same as the Keychain.
            if (failure != null && isConfigFileAbsence(failure.domain, failure.code)) return
            error("config file delete failed: ${failure?.localizedDescription}")
        }
    }
}
