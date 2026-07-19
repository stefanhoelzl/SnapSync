package app.snapsync.ports

import app.snapsync.model.ConfigFileDecode
import app.snapsync.model.EventConfig
import app.snapsync.model.decodeConfigFile

import kotlinx.coroutines.flow.StateFlow

/**
 * A single read of the persisted config, with **three** outcomes — the distinction the upload
 * extension's reconciliation depends on.
 *
 * "No config" means *this device left the event* to the reconciler: it clears the persisted
 * `joinedEventId` marker (capability `event-rejoin-reconciliation`). So an **unreadable** config —
 * the normal state on a locked device before this change, since the item was stored `WhenUnlocked` —
 * must never be reported as an **absent** one. It used to be, and the result was a *false leave* on
 * every OS-scheduled invocation: the marker was cleared, and the next readable cycle paid for a full
 * re-join reconciliation (a device listing, an atomic ledger clear-and-seed, and a discovery-cursor
 * reset forcing a complete library re-enumeration) — for ever, without the marker ever settling.
 *
 * Decision record: `changes/archive/…-fix-locked-device-keychain-access`.
 */
sealed interface ConfigRead {

    /** A config is persisted and decodes. */
    data class Joined(val config: EventConfig) : ConfigRead

    /**
     * There is definitively no usable config: the item is absent, **or** it is a legacy item that does
     * not decode (e.g. one written before `minPhotoDate` existed — capability `photo-selection-policy`,
     * where reading as no-config is the deliberate safe outcome). This is the only outcome that may
     * drive the leave-side reconciliation.
     */
    data object None : ConfigRead

    /** The store could not be read (protected data unavailable). Says **nothing** about membership. */
    data class Unavailable(val status: Int) : ConfigRead
}

/**
 * The three-state read port. [ConfigSource]'s `StateFlow` deliberately keeps its two-state shape for
 * the UI (which cannot run while protected data is unavailable anyway); readers that *act on absence*
 * — the extension's cycle — must use this instead.
 */
interface ConfigReader {
    fun read(): ConfigRead
}

/**
 * Map a raw Keychain read to a [ConfigRead]. Pure, so the branch that matters — *unreadable is not
 * absent* — is tested on JVM **and** the iOS simulator. [decode] returns `null` for an item that does
 * not decode, which is a [ConfigRead.None] (an undecodable item is genuinely unusable), never an
 * [ConfigRead.Unavailable] (which would mean "try again later"). Since the migration finale this
 * serves only the READ-ONLY legacy-Keychain fallback ([configReadViaFile]'s `fallback`); the
 * write-through is ended.
 */
fun configReadFrom(read: KeychainRead, decode: (String) -> EventConfig?): ConfigRead = when (read) {
    is KeychainRead.Found -> decode(read.value)?.let(ConfigRead::Joined) ?: ConfigRead.None
    KeychainRead.Absent -> ConfigRead.None
    is KeychainRead.Unavailable -> ConfigRead.Unavailable(read.status)
}

/**
 * The three answers a raw config-**file** read can give (migration step 11a: the config's storage of
 * record is an App-Group file; since the migration finale, saves and clears touch the file ALONE —
 * the Keychain write-through is ended — while the READ keeps a read-only legacy-Keychain fallback
 * until the post-ship Stage-2 change, capability `event-rejoin-reconciliation`). The platform
 * adapter maps its file-IO errors onto these — using the pure absence classifier
 * (`isConfigFileAbsence`, `model/`) so "genuinely missing" admits **only** the not-found error
 * class and every other failure stays on the unreadable side.
 */
sealed interface ConfigFileRead {

    /** The file exists and was read; [text] is its (not yet decoded) content. */
    data class Content(val text: String) : ConfigFileRead

    /**
     * The file genuinely does not exist (not-found error class **only**) — the only outcome that
     * may consult the read-only legacy-Keychain fallback, and — with the fallback also definitively
     * empty — the only road to "this device left the event". (The Stage-2 flip — reinstall = left,
     * the fallback deleted — is a designated POST-SHIP change gated on production soak; capability
     * `event-rejoin-reconciliation` records the staging.)
     */
    data object Missing : ConfigFileRead

    /** The read failed for any other reason (e.g. protected data unavailable). Never absence. */
    data class Failed(val status: Int, val detail: String) : ConfigFileRead
}

/**
 * `ConfigRead.Unavailable.status` sentinel for a config file whose *content* this build cannot
 * positively interpret (`ConfigFileDecode.Foreign`: a future envelope version, or not an envelope).
 * Not an `OSStatus` and not a Cocoa error code — those surfaces report real platform codes; this
 * value marks the decode-side unreadable so a log line can tell the two apart.
 */
const val CONFIG_FILE_FOREIGN_STATUS: Int = -1

/**
 * `ConfigRead.Unavailable.status` sentinel for a **current-version** envelope whose payload does
 * not decode ([ConfigFileDecode.Unusable]). Unreadable, not a leave: unlike the Keychain legacy
 * item (whose undecodability was a known, deliberate re-join path), an unusable file this
 * adapter's own atomic writes should make unreachable is evidence of something unexplained —
 * and an unexplained state must defer, never clear the join marker. Distinct from
 * [CONFIG_FILE_FOREIGN_STATUS] so a device log can tell the two apart.
 */
const val CONFIG_FILE_UNUSABLE_STATUS: Int = -2

/**
 * The file-backed config read, pure so every branch runs on JVM **and** the iOS simulator
 * (capability `event-link`; the file-store sibling of [configReadFrom]):
 *
 * - [ConfigFileRead.Content] → decode via the versioned envelope (`decodeConfigFile`, `model/`):
 *   valid → [ConfigRead.Joined]; same-version-but-unusable → [ConfigRead.Unavailable] with
 *   [CONFIG_FILE_UNUSABLE_STATUS] (the Keychain-side legacy rule does NOT transfer — see the
 *   sentinel's doc); foreign → [ConfigRead.Unavailable] with [CONFIG_FILE_FOREIGN_STATUS] (a
 *   future build's file must never read as a leave).
 * - [ConfigFileRead.Missing] → consult [fallback] (the READ-ONLY legacy-Keychain reader — the
 *   write-through is ended, but this branch ships to the installed base as ONE merge, so at ship
 *   time every joined production device is a pre-11a device whose file never existed; without the
 *   fallback the flip would silently log out the entire installed base on update): a `Joined`
 *   answer is **migrated** into the file via [migrate] — best-effort, the answer is returned
 *   regardless so a failed write retries on the next read. After the migrate, [fallback] is
 *   consulted **again** (compare-and-repair): if the Keychain no longer holds the value just
 *   migrated — a concurrent save/clear in the other process landed between the read and the write,
 *   observable because a save/clear that runs while a legacy item still exists leaves the file
 *   newer than the item this read is holding — the file now holds a stale clobber, so [repair] is
 *   invoked with the fresh state and the **fresh** state is returned.
 *   `None` stays `None` (definitively not joined — no file **and** no legacy item) and
 *   `Unavailable` stays `Unavailable` (a locked-device Keychain probe proves nothing). The
 *   fallback's deletion — the true **reinstall = left** flip — is a designated post-ship change
 *   gated on production soak (capability `event-rejoin-reconciliation`).
 * - [ConfigFileRead.Failed] → [ConfigRead.Unavailable] with the platform's status. The fallback is
 *   deliberately NOT consulted: the file exists-or-unknowable, so answering from the Keychain could
 *   contradict it (e.g. a stale legacy copy after the file superseded it).
 */
fun configReadViaFile(
    file: ConfigFileRead,
    fallback: () -> ConfigRead,
    migrate: (EventConfig) -> Unit,
    repair: (ConfigRead) -> Unit,
): ConfigRead = when (file) {
    is ConfigFileRead.Content -> when (val decoded = decodeConfigFile(file.text)) {
        is ConfigFileDecode.Valid -> ConfigRead.Joined(decoded.config)
        ConfigFileDecode.Unusable -> ConfigRead.Unavailable(CONFIG_FILE_UNUSABLE_STATUS)
        is ConfigFileDecode.Foreign -> ConfigRead.Unavailable(CONFIG_FILE_FOREIGN_STATUS)
    }
    ConfigFileRead.Missing -> {
        val first = fallback()
        if (first !is ConfigRead.Joined) {
            first
        } else {
            migrate(first.config)
            val recheck = fallback()
            if (recheck == first) first else recheck.also(repair)
        }
    }
    is ConfigFileRead.Failed -> ConfigRead.Unavailable(file.status)
}

/**
 * The next `ConfigSource` StateFlow value after a trigger-time re-read (migration step 12: every
 * OS-callback flow re-reads the membership before acting on it, replacing the deleted unlock-hook
 * repair). Pure so the one branch that matters is tested on JVM and the simulator:
 *
 * - a **conclusive** read ([ConfigRead.Joined] / [ConfigRead.None]) replaces the value;
 * - an **unreadable** read ([ConfigRead.Unavailable]) **retains** [current] — the same
 *   keep-the-last-good posture as the status counts. Under the old cadence (reload only at the
 *   unlock notification) an unreadable reload was unreachable; at trigger cadence a transient read
 *   failure on a foreground entry would otherwise clear a good membership and flip the screen to
 *   the setup gate.
 */
fun configAfterReload(read: ConfigRead, current: EventConfig?): EventConfig? = when (read) {
    is ConfigRead.Joined -> read.config
    ConfigRead.None -> null
    is ConfigRead.Unavailable -> current
}

/**
 * The state port for the active config: a level-triggered holder whose current value is always
 * available synchronously — the persisted [EventConfig] (the joined `eventId` plus an optional
 * fetched `name`), or `null` when none has been provisioned yet. The setup gate observes this to
 * decide whether the "joined an event" step is satisfied. Like the permission seam, truth arrives
 * here and nowhere else. Combining the `eventId` with the compile-time upload host into the edge
 * upload URL is the consuming composition root's job, not this seam's.
 *
 * **This port cannot express "unreadable"** — `null` here means "no config, as far as this process can
 * tell". That is fine for the UI, and fatal for the reconciler; see [ConfigReader].
 */
interface ConfigSource {
    val config: StateFlow<EventConfig?>
}

/**
 * The command port for provisioning config: persist [config] and update the [ConfigSource].
 * Saving a config equal (field-for-field, incl. `name`) to the current one is an idempotent no-op;
 * saving a config differing in `eventId` **or** `name` replaces it and emits (a name-only change
 * updates the title without any ledger effect — the switch-reset on an `eventId` change is
 * orchestrated by the provision path, not this seam; see the event-link spec). [clear] is the inverse:
 * it removes the persisted config and updates the [ConfigSource] to `null` (an idempotent no-op when
 * none is persisted), and — like [save] — leaves the ledger untouched (the caller orchestrates any
 * ledger reset; see the `leave-event` capability). Implementations typically also implement
 * [ConfigSource] as one platform adapter; consumers depend on each port separately.
 */
interface ConfigStore {
    suspend fun save(config: EventConfig)

    suspend fun clear()
}
