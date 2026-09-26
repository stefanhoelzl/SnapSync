package app.snapsync.services.config

import app.snapsync.model.ConfigFileRead
import app.snapsync.model.ConfigRead
import app.snapsync.model.EventConfig
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.MembershipRead
import app.snapsync.model.encodeConfigFile
import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.DeletesAt
import app.snapsync.model.confirmedGone
import app.snapsync.model.instantToCutoff
import app.snapsync.ports.Clock
import app.snapsync.ports.Files
import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The config file in the shared area's root (a runtime-identity pin, `docs/architecture.md`): the **storage of
 * record** for the persisted [EventConfig], and its only storage. Both processes read and write it.
 */
const val CONFIG_FILE_NAME: String = "eventconfig.json"

/**
 * The membership's config (capability `join-event`): [ConfigSource]/[ConfigStore]/[ConfigReader] over one
 * **versioned-envelope file** in the shared area ([CONFIG_FILE_NAME]), and nothing else.
 *
 * That makes **reinstall = left the event** real: the shared container dies with the install, so a reinstalled
 * device reads definitively not joined and rejoins only by re-scanning the invite (capability `photo-sharing`).
 *
 * ⚠️ **"Not found" is solely load-bearing for the leave decision.** Only [FileResult.NotFound] reads as
 * [ConfigFileRead.Missing] — definitively not joined; every other answer ([FileResult.Denied], a locked device's
 * read; [FileResult.AreaUnavailable]; [FileResult.Failed]) is **unreadable**, and the caller defers. The
 * classification behind `NotFound` is the `Files` adapter's, pinned by the `Files` contract's "denied is never
 * not found" clause; widening it is a change to the leave decision.
 *
 * All decode/decision intelligence is pure and tested (`configReadViaFile` and the reload rules beside this class, the
 * envelope codec in `model/`); this class maps [Files] answers onto them.
 *
 * Seeds [config] synchronously at construction — a file read, never a database or a Keychain item — like the
 * adapter it replaced: a background launch before first unlock seeds `null` (the read is denied → unreadable,
 * never absent), and every trigger flow calls [reload] to repair it before acting. **Readers that act on the
 * absence of a config must use [read], not [config]** — see [ConfigRead].
 */
class ConfigService(
    private val files: Files,
    /** The wall clock the membership's own deadline is read against ([isPastDeletion]). */
    private val clock: Clock,
    private val log: Logger = Logger.withTag("fileConfig"),
) {

    private val initial = read()
    private val state = MutableStateFlow(initial.joinedOrNull())
    /**
     * The active membership as a level-triggered holder whose current value is always available synchronously — the
     * persisted [EventConfig], or `null` when none is provisioned **as far as this process can tell**. That is fine
     * for the UI, and fatal for anything that acts on absence: those read [membership] or [read].
     */
    val config: StateFlow<EventConfig?> = state

    /** Written wherever [state] is, so the two never disagree; `Unreadable` only until a conclusive read. */
    @Volatile
    private var membershipState: MembershipRead = membershipAfterReload(initial, MembershipRead.Unreadable)
    /**
     * The membership as three answers (capability `background-upload`; decision record `harden-seam-bug-classes`,
     * D11): [config]'s `null` merges "not joined" with "could not read it yet", and a reader that ACTS on absence —
     * the upload transitions, the silent-push receivers — must tell them apart and defer on the second.
     *
     * Read fresh at each use, never held. [MembershipRead.Unreadable] only until a conclusive read.
     */
    val membership: MembershipRead get() = membershipState

    suspend fun save(config: EventConfig) {
        // Deliberately NO equal-config early return: [state] can lag a cross-process writer, so an outer guard
        // could skip a write the file actually needs. The atomic rewrite of an equal value is harmless, and the
        // [StateFlow] conflates equal values, so the port's no-redundant-emission contract holds.
        val written = files.write(FileArea.SHARED, CONFIG_FILE_NAME, encodeConfigFile(config).encodeToByteArray())
        check(written is FileResult.Ok) { "config file write failed: $written" }
        state.value = config
        membershipState = MembershipRead.Member(config)
    }

    suspend fun clear() {
        // A throw propagates: the file stays, this build stays joined, and the leave retries visibly rather than
        // half-completing. An unreachable area is NOT "nothing to delete" — returning there would null the flow
        // while a file it never touched survived to resurrect the membership at the next launch.
        when (val deleted = files.delete(FileArea.SHARED, CONFIG_FILE_NAME)) {
            is FileResult.Ok, FileResult.NotFound -> Unit // deleting an absent file is success
            else -> error("config file delete failed: $deleted")
        }
        state.value = null
        membershipState = MembershipRead.NotMember
    }

    /** The three-state read (capability `join-event`): the pure `configReadViaFile` over this file, and nothing else. */
    fun read(): ConfigRead {
        val read = configReadViaFile(readFile())
        if (read is ConfigRead.Unavailable) {
            log.w { "config file unreadable (status=${read.status}) — NOT 'no config'; caller must defer" }
        }
        return read
    }

    /**
     * Re-read the file into [config]: cross-process writers do not notify this process's [StateFlow], and a
     * pre-first-unlock construction seeded `null`. The trigger flows call this at every OS entry, so an
     * **unreadable** read retains the last good value (`configAfterReload`): at this cadence a transient failure
     * would otherwise clear a good membership mid-session and flip the screen to the setup gate.
     */
    fun reload() {
        val read = read()
        state.value = configAfterReload(read, state.value)
        membershipState = membershipAfterReload(read, membershipState)
    }

    /**
     * Has this membership's own retention deadline [deletesAt] passed (capability `manage-membership`)? The self-leave's
     * OFFLINE witness — see [confirmedGone] for why it is exact, and why a `null` deadline is never reached.
     */
    fun isPastDeletion(deletesAt: DeletesAt?): Boolean = confirmedGone(deletesAt, instantToCutoff(clock.now()))

    /** `null` for both *absent* and *unreadable* — acceptable for the UI-facing [config], never for the reconciler. */
    private fun ConfigRead.joinedOrNull(): EventConfig? = (this as? ConfigRead.Joined)?.config

    private fun readFile(): ConfigFileRead = when (val read = files.read(FileArea.SHARED, CONFIG_FILE_NAME)) {
        is FileResult.Ok -> read.value.decodeUtf8()?.let { ConfigFileRead.Content(it) }
            ?: ConfigFileRead.Failed(status = 0, detail = "config file is not UTF-8")
        FileResult.NotFound -> ConfigFileRead.Missing
        // A missing area is a provisioning/entitlement failure, not evidence about membership: unreadable.
        FileResult.AreaUnavailable -> ConfigFileRead.Failed(status = 0, detail = "the shared area is unavailable")
        is FileResult.Denied -> ConfigFileRead.Failed(status = (read.code ?: 0L).toInt(), detail = read.detail)
        is FileResult.Failed -> ConfigFileRead.Failed(status = (read.code ?: 0L).toInt(), detail = read.detail)
    }

    private fun ByteArray.decodeUtf8(): String? =
        runCatchingCancellable { decodeToString(throwOnInvalidSequence = true) }.getOrNull()
}
