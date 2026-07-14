package app.snapsync.album

import app.snapsync.keychain.KeychainRead

/**
 * Where the `eventId → albumLocalId` map should be read from, and whether the legacy Keychain item
 * still needs migrating (capability `event-album`).
 *
 * The map used to live in the **Keychain**, which was a mistake: the upload extension reads it while
 * placing a completed upload into the event album, and the OS invokes that extension when the device
 * is idle — i.e. locked — where a `WhenUnlocked` Keychain item is unreadable. It now lives in the
 * App-Group `NSUserDefaults` suite (like the discovery cursor), whose container inherits
 * `NSFileProtectionCompleteUntilFirstUserAuthentication` and is therefore background-readable **by
 * construction**, with no accessibility class to get wrong.
 *
 * Nothing is lost by moving: the `event-album` spec requires only "a shared store, readable and
 * writable by both processes, that survives leave" — it never pinned the Keychain — and the map is a
 * self-healing cache anyway (`AlbumCoordinator.ensureAlbum` re-creates or reuses by name). The only
 * property the Keychain added was surviving uninstall, which nothing requires.
 *
 * Decision record: `changes/archive/…-fix-locked-device-keychain-access`.
 */
sealed interface AlbumMapSource {

    /** Read from the App Group (`null` when nothing has ever been stored). Nothing to migrate. */
    data class Current(val raw: String?) : AlbumMapSource

    /** A legacy Keychain map exists: copy [raw] into the App Group, then delete the Keychain item. */
    data class Migrate(val raw: String) : AlbumMapSource

    /**
     * The legacy item could not be read (protected data unavailable). Do **nothing** — do not delete it,
     * and do not conclude the map is empty. The next readable call migrates it.
     */
    data object Retry : AlbumMapSource
}

/**
 * Decide once, purely, so the one-shot migration is tested on JVM **and** the simulator rather than
 * living in an untested iOS file.
 *
 * The App Group wins whenever it holds anything: migration is one-shot, and a second read must not
 * re-migrate (the Keychain item is gone by then anyway).
 */
fun albumMapSource(stored: String?, legacy: KeychainRead): AlbumMapSource = when {
    stored != null -> AlbumMapSource.Current(stored)
    legacy is KeychainRead.Found -> AlbumMapSource.Migrate(legacy.value)
    legacy is KeychainRead.Unavailable -> AlbumMapSource.Retry
    else -> AlbumMapSource.Current(null) // genuinely nothing anywhere: a fresh install
}
