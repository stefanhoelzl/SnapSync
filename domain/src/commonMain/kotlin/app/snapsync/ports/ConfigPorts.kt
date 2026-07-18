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
 * [ConfigRead.Unavailable] (which would mean "try again later").
 */
fun configReadFrom(read: KeychainRead, decode: (String) -> EventConfig?): ConfigRead = when (read) {
    is KeychainRead.Found -> decode(read.value)?.let(ConfigRead::Joined) ?: ConfigRead.None
    KeychainRead.Absent -> ConfigRead.None
    is KeychainRead.Unavailable -> ConfigRead.Unavailable(read.status)
}

/**
 * The three answers a raw config-**file** read can give (migration step 11a: the config's storage of
 * record is an App-Group file; the Keychain copy is kept written-through for revert safety until the
 * migration finale deletes it). The platform adapter maps its file-IO errors onto these — using the
 * pure absence classifier (`isConfigFileAbsence`, `model/`) so "genuinely missing" admits **only**
 * the not-found error class and every other failure stays on the unreadable side.
 */
sealed interface ConfigFileRead {

    /** The file exists and was read; [text] is its (not yet decoded) content. */
    data class Content(val text: String) : ConfigFileRead

    /**
     * The file genuinely does not exist (not-found error class **only**) — the only outcome that
     * may consult the fallback copy, and — with the fallback also definitively empty — the only
     * road to "this device left the event".
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
 * - [ConfigFileRead.Missing] → consult [fallback] (the Keychain copy, for as long as it is written
 *   through): a `Joined` answer is **migrated** into the file via [migrate] — best-effort, the
 *   answer is returned regardless so a failed write retries on the next read — which is what closes
 *   the update-in-place false-leave window (the OS can run the extension before the user ever opens
 *   the updated app; both processes carry this same adapter, so whichever reads first migrates).
 *   After the migrate, [fallback] is consulted **again** (compare-and-repair): if the Keychain no
 *   longer holds the value just migrated — a concurrent save/clear in the other process landed
 *   between the read and the write — the file now holds a stale clobber, so [repair] is invoked
 *   with the fresh state (overwrite on `Joined`, delete otherwise) and the **fresh** state is
 *   returned. This shrinks the stale-migrate window from the whole read-to-write span to the
 *   instruction width between the re-read and the return.
 *   `None` stays `None` (definitively not joined — no file **and** no Keychain item) and
 *   `Unavailable` stays `Unavailable` (a locked-device Keychain probe proves nothing).
 * - [ConfigFileRead.Failed] → [ConfigRead.Unavailable] with the platform's status. The fallback is
 *   deliberately NOT consulted: the file exists-or-unknowable, so answering from the Keychain could
 *   contradict it (e.g. a stale copy after a partial save).
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
