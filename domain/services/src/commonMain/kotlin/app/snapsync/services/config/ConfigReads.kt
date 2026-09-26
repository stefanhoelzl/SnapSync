package app.snapsync.services.config

import app.snapsync.model.ConfigFileDecode
import app.snapsync.model.ConfigFileRead
import app.snapsync.model.ConfigRead
import app.snapsync.model.EventConfig
import app.snapsync.model.MembershipRead
import app.snapsync.model.decodeConfigFile

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
 * The next [ConfigService.config] value after a trigger-time re-read (migration step 12: every
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
 * The next [MembershipRead] after a re-read — [configAfterReload]'s three-valued twin. A conclusive read replaces
 * it; an unreadable one retains the last conclusive answer, and is [MembershipRead.Unreadable] only when there has
 * never been one.
 */
fun membershipAfterReload(read: ConfigRead, current: MembershipRead): MembershipRead = when (read) {
    is ConfigRead.Joined -> MembershipRead.Member(read.config)
    ConfigRead.None -> MembershipRead.NotMember
    is ConfigRead.Unavailable -> current
}
