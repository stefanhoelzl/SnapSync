package app.snapsync.config

import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSFileNoSuchFileError
import platform.Foundation.NSFileReadNoSuchFileError
import platform.Foundation.NSPOSIXErrorDomain

/** POSIX `ENOENT`, as `NSPOSIXErrorDomain` reports it. Foundation exposes no constant for it. */
private const val POSIX_ENOENT: Long = 2L

/**
 * Whether a file-read error means the file is **genuinely absent** — the only error class that may
 * read as "no config" (settle-list ⑥, decision record: `changes/archive/…-migrate-config-to-app-group-file`).
 *
 * Grounded on Apple's data-protection contract: reading a **protected** file before first unlock
 * fails with a permission-class error (`NSFileReadNoPermissionError` 257 / POSIX `EPERM`), never
 * with not-found — so not-found is definitive absence, and **any other error whatsoever** is
 * *unreadable*: the caller must defer, exactly as an unreadable Keychain item defers. Admitting an
 * unknown error into the absent class would recreate the false-leave bug this whole seam exists to
 * prevent, which is why the `else` arm answers `false` rather than guessing.
 *
 * **Why it lives here and not in `model/`.** Its inputs are an `NSError` domain and code — a
 * platform encoding, not a platform-independent fact — so translating them is an adapter's job
 * (spec `module-architecture`). It sat in `model/` to be exercised on both targets, but a JVM run
 * could only assert integer literals against themselves; here the test can name
 * `NSFileReadNoSuchFileError` and fail if Apple ever moves it.
 *
 * The neutral fact this reports into is [app.snapsync.ports.ConfigFileRead], and the rule that turns
 * a `Missing` into a leave stays in `configReadViaFile` — nothing about the *decision* moved.
 */
fun isConfigFileAbsence(domain: String?, code: Long): Boolean = when (domain) {
    NSCocoaErrorDomain -> code == NSFileReadNoSuchFileError || code == NSFileNoSuchFileError
    NSPOSIXErrorDomain -> code == POSIX_ENOENT
    else -> false
}
