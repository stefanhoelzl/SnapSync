package app.snapsync.album

import app.snapsync.keychain.IosKeychain
import app.snapsync.ports.SecureStore

/**
 * Where the event-album map lived **before** it moved to the App-Group preferences (capability `event-album`): an
 * unscoped Keychain item the extension could not read while the device was locked. The album-map service migrates
 * it once and deletes it (`AlbumMapService`); this is its one seat in production Kotlin, pinned as runtime
 * identity (`docs/architecture.md`) — a drifted service or account would make the migration a silent no-op.
 */
fun legacyAlbumMapKeychain(): SecureStore = IosKeychain(service = "app.snapsync.album", account = "albummap")
