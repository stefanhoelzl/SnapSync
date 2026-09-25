package app.snapsync.ports

import app.snapsync.model.ConfigFileDecode
import app.snapsync.model.EventConfig
import app.snapsync.model.decodeConfigFile

import kotlinx.coroutines.flow.StateFlow
import app.snapsync.model.ConfigFileRead
import app.snapsync.model.ConfigRead
import app.snapsync.model.MembershipRead

/**
 * The three-state read port. [ConfigSource]'s `StateFlow` deliberately keeps its two-state shape for
 * the UI (which cannot run while protected data is unavailable anyway); readers that *act on absence*
 * — the extension's cycle — must use this instead.
 */
interface ConfigReader {
    fun read(): ConfigRead
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
 * not decode ([ConfigFileDecode.Unusable]). Unreadable, not a leave: unlike the retired Keychain
 * legacy item (whose undecodability was a known, deliberate re-join path), an unusable file this
 * adapter's own atomic writes should make unreachable is evidence of something unexplained —
 * and an unexplained state must defer, never read as a leave. Distinct from
 * [CONFIG_FILE_FOREIGN_STATUS] so a device log can tell the two apart.
 */
const val CONFIG_FILE_UNUSABLE_STATUS: Int = -2

/**
 * The file-backed config read, pure so every branch runs on JVM **and** the iOS simulator
 * (capability `join-event`):
 *
 * - [ConfigFileRead.Content] → decode via the versioned envelope (`decodeConfigFile`, `model/`):
 *   valid → [ConfigRead.Joined]; same-version-but-unusable → [ConfigRead.Unavailable] with
 *   [CONFIG_FILE_UNUSABLE_STATUS] (an unexplained state defers — see the sentinel's doc); foreign →
 *   [ConfigRead.Unavailable] with [CONFIG_FILE_FOREIGN_STATUS] (a future build's file must never
 *   read as a leave).
 * - [ConfigFileRead.Missing] → [ConfigRead.None], **definitively not joined**, consulting nothing.
 *   Until the Stage-2 change this branch consulted a read-only legacy-Keychain fallback, migrated
 *   any membership it found into the file, and re-checked it (compare-and-repair) — the whole
 *   installed base's update path under the migration's ship-at-once model. That population is
 *   gone: the fallback shipped in 11a, and both it and the finale are ancestors of `v0.1`, the
 *   first App Store release (decision record: `changes/archive/…-retire-legacy-config-fallback` D1).
 * - [ConfigFileRead.Failed] → [ConfigRead.Unavailable] with the platform's status: the file
 *   exists-or-unknowable, which is never evidence of a leave.
 *
 * It stays a `:domain` function rather than collapsing into the adapter (`join-event` requires the
 * read algorithm be pure and `commonTest`-covered on both targets): the one decision in the app
 * that can silently log a user out must not be testable on macOS only.
 */
fun configReadViaFile(file: ConfigFileRead): ConfigRead = when (file) {
    is ConfigFileRead.Content -> when (val decoded = decodeConfigFile(file.text)) {
        is ConfigFileDecode.Valid -> ConfigRead.Joined(decoded.config)
        ConfigFileDecode.Unusable -> ConfigRead.Unavailable(CONFIG_FILE_UNUSABLE_STATUS)
        is ConfigFileDecode.Foreign -> ConfigRead.Unavailable(CONFIG_FILE_FOREIGN_STATUS)
    }
    ConfigFileRead.Missing -> ConfigRead.None
    is ConfigFileRead.Failed -> ConfigRead.Unavailable(file.status)
}

/**
 * The next `ConfigSource` StateFlow value after a trigger-time re-read (migration step 12: every
 * OS-callback flow re-reads the membership before acting on it, replacing the deleted unlock-hook
 * repair). Pure so the one branch that matters is tested on JVM and the simulator:
 *
 * - a **conclusive** read ([ConfigRead.Joined] / [ConfigRead.None]) replaces the value;
 * Absence: the returned null means "definitively not joined" and ONLY that — the three-state
 * [ConfigRead] is precisely what keeps "could not tell" out of it, by retaining the last good value
 * instead. This function is where that law is enforced for the membership.
 *
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

    /**
     * The membership as three answers (capability `background-upload`; decision record `harden-seam-bug-classes`,
     * D11): [config]'s `null` merges "not joined" with "could not read it yet", and a reader that ACTS on absence —
     * the upload transitions, the silent-push receivers — must tell them apart and defer on the second.
     *
     * Read fresh at each use, never held. The default derives it from [config], and so never answers
     * [MembershipRead.Unreadable]: right for a source that cannot fail to read. The file-backed store overrides it.
     */
    val membership: MembershipRead
        get() = config.value?.let(MembershipRead::Member) ?: MembershipRead.NotMember
}

/**
 * The next [MembershipRead] after a re-read — [configAfterReload]'s three-valued twin. A conclusive read replaces
 * it; an unreadable one retains the last conclusive answer, and is [MembershipRead.Unreadable] only when there has
 * never been one.
 */
fun membershipAfterReload(read: ConfigRead, current: MembershipRead): MembershipRead = when (read) {
    is ConfigRead.Joined -> MembershipRead.Member(read.config)
    ConfigRead.None -> MembershipRead.NotMember
    is ConfigRead.Unavailable -> current
}

/**
 * Re-read the persisted membership into this process's [ConfigSource].
 *
 * A port, not a `suspend () -> Unit`: on iOS the re-read is an App-Group file read, which is a platform
 * read the composition must not hand the core as a bare lambda (law "Ports are the I/O boundary named for
 * the need", `docs/architecture.md`). Every trigger flow re-reads before acting, because
 * cross-process writes and a pre-first-unlock seed never notify this process's state flow. An implementation
 * that holds its membership in-process only (the world harness) refreshes nothing, and says so by
 * implementing this as a no-op rather than by the composition defaulting it away.
 */
fun interface ConfigRefresh {
    suspend fun refresh()
}

/**
 * The command port for provisioning config: persist [config] and update the [ConfigSource].
 * Saving a config equal (field-for-field, incl. `name`) to the current one is an idempotent no-op;
 * saving a config differing in `eventId` **or** `name` replaces it and emits (a name-only change
 * updates the title without any ledger effect — the switch-reset on an `eventId` change is
 * orchestrated by the provision path, not this seam; see the event-link spec). [clear] is the inverse:
 * it removes the persisted config and updates the [ConfigSource] to `null` (an idempotent no-op when
 * none is persisted), and — like [save] — leaves the ledger untouched (the caller orchestrates any
 * ledger reset; see the `manage-membership` capability). Implementations typically also implement
 * [ConfigSource] as one platform adapter; consumers depend on each port separately.
 */
interface ConfigStore {
    suspend fun save(config: EventConfig)

    suspend fun clear()
}
