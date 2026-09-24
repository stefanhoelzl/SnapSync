package app.snapsync.ports

/**
 * Whether the photo library has changed since an earlier moment — the one question the app process's walk memo
 * asks before it answers a walk without enumerating the library (capability `sync-ledger`, "An unchanged library
 * is answered from the walk memo").
 *
 * Named for that need rather than for the platform object behind it: on iOS a token is
 * `PHPhotoLibrary.currentChangeToken`, compared with `isEqual`, but nothing here archives, persists or feeds one
 * to a change query. It is **not** the discovery cursor `changes/archive/2026-09-21-always-full-enumerate`
 * removed: a token read here lives in memory beside the walk it was read before, and dies with the process.
 *
 * Measured (SE2, iOS 26.6.2): a read plus a comparison costs about 2 ms in the background (darwinbg) role,
 * against 1.3–2.0 s for a walk of 4.5k–6.3k assets; the token held across idle periods and relaunches, moved on
 * every asset creation and album add the app made, was never equal across a library change, and kept moving for
 * 1–9 s of trailing changes after any write. Not yet measured: whether a change made **outside** the process (a
 * Camera photo, an iCloud sync) always moves it — a device check of `changes/own-work-per-wake` (task 7.4),
 * until which the memo is not relied on.
 */
interface LibraryChangeTokenRead {

    /**
     * The library's token **now**, or `null` when the platform gave none — which a caller treats as "cannot
     * tell", never as "unchanged": no memo entry is served or stored against a `null`.
     *
     * Read **before** the walk it is stored with, so a change landing while the walk runs leaves the stored token
     * stale and the next walk enumerates afresh — never the reverse.
     */
    suspend fun current(): LibraryChangeToken?
}

/**
 * One reading of the library's change token: opaque, comparable, and held only in memory.
 */
interface LibraryChangeToken {

    /**
     * Whether [other] was read while the library stood exactly as it did when this one was read. Compared by
     * **value** — two reads of an unchanged library are distinct platform objects that compare equal — never by
     * identity, which would never match and so would make the memo walk every time.
     */
    fun sameLibraryAs(other: LibraryChangeToken): Boolean
}
