@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.config

import app.snapsync.model.EventConfig
import app.snapsync.model.encodeConfigFile
import app.snapsync.ports.ConfigFileRead
import app.snapsync.ports.ConfigRead
import app.snapsync.ports.ConfigReader
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import app.snapsync.ports.configAfterReload
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
 * ([CONFIG_FILE_NAME]). Since the migration finale the file is the only storage a WRITE ever
 * touches — the 11a Keychain **write-through is ended** (save/clear are file-only; the revert
 * direction is sacrificed, consistent with fix-forward) — while the READ keeps the
 * adapter-resident migration fallback through the read-only [KeychainConfigReader]: this branch
 * ships to the installed base as ONE merge, so at ship time every joined production device is a
 * pre-11a device whose file never existed, and a fallback-less missing-file read would silently
 * log the entire installed base out on update. The fallback's deletion — the true
 * **reinstall = left the event** flip — is a designated post-ship change gated on production soak
 * (capability `event-rejoin-reconciliation` records the staging; decision record: the
 * migration-finale change's design.md, D4).
 *
 * All decode/decision intelligence is pure and `commonTest`-covered (`configReadViaFile` in
 * `ports/`, the envelope codec + absence classifier in `model/`); this class only performs the
 * file IO and maps its `NSError`s onto [ConfigFileRead]. Writes are **atomic** ([NSDataWritingAtomic]:
 * temp file + rename) under [NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication] —
 * the same protection class as the ledger and download DBs, readable while locked after first
 * unlock, which the OS-scheduled (usually-locked) extension cycle requires.
 *
 * Seeds [config] synchronously at construction, exactly like the Keychain store it replaced: a
 * background launch before first unlock seeds `null` (the CUFUA read fails permission-class →
 * unreadable, never absent) and every trigger flow calls [reload] to repair it before acting
 * (migration step 12 — the trigger-time re-read replaced the unlock hook).
 * **Readers that act on the absence of a config must use [read], not [config]** — see [ConfigRead].
 */
class FileBackedConfigStore(
    private val keychainReader: KeychainConfigReader = KeychainConfigReader(),
    private val log: Logger = Logger.withTag("fileConfig"),
) : ConfigSource, ConfigStore, ConfigReader {

    private val state = MutableStateFlow(read().joinedOrNull())
    override val config: StateFlow<EventConfig?> = state

    override suspend fun save(config: EventConfig) {
        // Deliberately NO equal-config early return: [state] can lag a cross-process writer, so an
        // outer guard could skip a write the file actually needs. The atomic rewrite of an equal
        // value is harmless, and the [StateFlow] conflates equal values, so the port's
        // no-redundant-emission contract holds.
        writeFile(encodeConfigFile(config))
        state.value = config
    }

    override suspend fun clear() {
        // Legacy item FIRST, file second — the 11a clear ordering's surviving half: while the
        // read fallback lasts, a file-only clear would leave exactly the missing-file +
        // item-present state the fallback resurrects, silently undoing the leave on every
        // migrated device. Cleared this way, a crash between the two leaves the file present —
        // this build stays joined and the leave simply retries. Both halves are idempotent; no
        // config VALUE is ever written to the Keychain (the write-through stays ended).
        // A throw here propagates (the 11a posture): the file stays, this build stays joined, and
        // the leave retries visibly — swallowing it would proceed to the file delete and mint the
        // resurrection state. (IosKeychain.delete already swallows SecItemDelete statuses; only an
        // allocation failure can throw.)
        keychainReader.deleteLegacyItem()
        deleteFile()
        state.value = null
    }

    /**
     * The three-state read (capability `event-link`): the pure `configReadViaFile` over this
     * process's file IO, consulting the READ-ONLY legacy-Keychain fallback **only** on a
     * definitively missing file — and migrating a found membership into the file (with the 11a
     * compare-and-repair) so the next read answers from the file alone. See the class doc for why
     * the read fallback outlives the write-through until the post-ship Stage-2 change.
     */
    override fun read(): ConfigRead {
        val read = configReadViaFile(
            file = readFileRaw(),
            fallback = { keychainReader.read() },
            migrate = { cfg ->
                runCatching { writeFile(encodeConfigFile(cfg)) }
                    .onSuccess { log.i { "config migrated: legacy Keychain → App-Group file" } }
                    .onFailure { log.w(it) { "config migration write failed — the next read retries" } }
            },
            // Compare-and-repair (the pure algorithm re-reads the fallback after the migrate): a
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
     * Re-read the file into [config]: cross-process writers do not notify this process's
     * [StateFlow], and a pre-first-unlock construction seeded `null`. Since migration step 12 the
     * trigger flows call this at **every** OS entry (foreground, silent push, backstop — replacing
     * the deleted unlock-hook repair), so an **unreadable** read retains the last good value
     * (`configAfterReload`, pure and tested): at this cadence a transient failure would otherwise
     * clear a good membership mid-session and flip the screen to the setup gate.
     */
    fun reload() {
        state.value = configAfterReload(read(), state.value)
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
