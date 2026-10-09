package app.snapsync.services.config

import app.snapsync.model.ConfigFileDecode
import app.snapsync.model.ConfigRead
import app.snapsync.model.EventConfig
import app.snapsync.model.FileResult
import app.snapsync.model.MembershipRead
import app.snapsync.model.decodeConfigFile
import app.snapsync.model.runCatchingCancellable

/**
 * The file-backed config read — one read of the config file in the shared area, as the `Files` port answered it —
 * pure so every branch runs in `commonTest`:
 *
 * - [FileResult.Ok] → decode via the versioned envelope (`decodeConfigFile`, `model/`): valid →
 *   [ConfigRead.Joined]; same-version-but-unusable → [ConfigRead.Unavailable] (unlike the retired Keychain legacy
 *   item, whose undecodability was a deliberate re-join path, an unusable file this app's own atomic writes should
 *   make unreachable is evidence of something unexplained — and an unexplained state defers); foreign →
 *   [ConfigRead.Unavailable] (a future build's file must never read as a leave); not UTF-8 → [ConfigRead.Unavailable].
 * - [FileResult.NotFound] → [ConfigRead.None], **definitively not joined**, consulting nothing — the sole road to
 *   "this device left the event". An App-Group container dies with the install, so this is also what makes a
 *   reinstall a leave. Until the Stage-2 change this branch consulted a read-only
 *   legacy-Keychain fallback (decision record: `changes/archive/…-retire-legacy-config-fallback` D1); there is no
 *   second opinion any more, so the `Files` adapter's not-found classification is solely load-bearing: widening it is
 *   a change to the leave decision, not an error-handling detail.
 * - every other answer ([FileResult.Denied] — a locked device's read —, [FileResult.AreaUnavailable], a missing
 *   App-Group entitlement, and [FileResult.Failed]) → [ConfigRead.Unavailable]: the file exists-or-unknowable, which
 *   is never evidence of a leave.
 *
 * Each [ConfigRead.Unavailable] carries a distinct detail, so a device log can tell the causes apart.
 *
 * It stays a `:domain` function rather than collapsing into the service's I/O (the read
 * algorithm must be pure and `commonTest`-covered on both targets): the one decision in the app that can silently log a
 * user out must not be testable on macOS only.
 */
fun configReadViaFile(file: FileResult<ByteArray>): ConfigRead = when (file) {
    is FileResult.Ok -> when (val text = file.value.decodeUtf8()) {
        null -> ConfigRead.Unavailable("config file is not UTF-8")
        else -> configReadOf(text)
    }
    FileResult.NotFound -> ConfigRead.None
    FileResult.AreaUnavailable -> ConfigRead.Unavailable("the shared area is unavailable")
    is FileResult.Denied -> ConfigRead.Unavailable("denied (code=${file.code}): ${file.detail}")
    is FileResult.Failed -> ConfigRead.Unavailable("failed (code=${file.code}): ${file.detail}")
}

private fun configReadOf(text: String): ConfigRead = when (val decoded = decodeConfigFile(text)) {
    is ConfigFileDecode.Valid -> ConfigRead.Joined(decoded.config)
    ConfigFileDecode.Unusable -> ConfigRead.Unavailable("config file is unusable: its payload does not decode")
    is ConfigFileDecode.Foreign -> ConfigRead.Unavailable("config file is foreign: ${decoded.reason}")
}

private fun ByteArray.decodeUtf8(): String? =
    runCatchingCancellable { decodeToString(throwOnInvalidSequence = true) }.getOrNull()

/**
 * The next [ConfigService.config] value after a trigger-time re-read (migration step 12: every
 * OS-callback flow re-reads the membership before acting on it, replacing the deleted unlock-hook
 * repair). Pure so the one branch that matters is tested in `commonTest`:
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
