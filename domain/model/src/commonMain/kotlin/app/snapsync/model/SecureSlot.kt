package app.snapsync.model

/**
 * One addressed item of the `SecureStore` port: a (service, account) pair, and whether it lives in the store's
 * **shared** place — on iOS the App-Group Keychain access group both processes are entitled to — or is searched
 * without naming one ([shared] `false`: whatever groups this process may read; a write lands where the platform
 * chooses from the writing build's entitlements).
 *
 * Unscoped placement is how the app and the extension once came to hold two different device ids, so every
 * unscoped slot is a pinned inventory (`RuntimeIdentityTest`), and new items name their place.
 */
data class SecureSlot(val service: String, val account: String, val shared: Boolean)

/**
 * The items this app keeps in the `SecureStore` — runtime identity: the installed base holds them under exactly
 * these addresses, and a re-valued one silently addresses a different real item (`docs/architecture.md`).
 */
object SecureSlots {

    /** The device id, in the shared group both processes read (capability `photo-sharing`). */
    val DEVICE_ID = SecureSlot(service = "app.snapsync.deviceid", account = "deviceid", shared = true)

    /**
     * The same item searched without a group: where an older build may have filed the device id, and the one
     * place the app adopts it from before minting (never the extension).
     */
    val DEVICE_ID_LEGACY = DEVICE_ID.copy(shared = false)

    /** The attestation token and its App Attest key id (capability `privacy-security`). Unscoped (pinned). */
    val ATTEST_TOKEN = SecureSlot(service = "app.snapsync.attest", account = "token", shared = false)
    val ATTEST_KEY_ID = SecureSlot(service = "app.snapsync.attest", account = "keyid", shared = false)

    /** The pre-App-Group event-album map, migrated once and deleted (capability `event-album`). Unscoped (pinned). */
    val ALBUM_MAP_LEGACY = SecureSlot(service = "app.snapsync.album", account = "albummap", shared = false)
}

/**
 * Who may create the device identity (capability `photo-sharing`). Two roles, and only one may mint.
 */
enum class DeviceIdentityRole {

    /**
     * The app. Reads the shared slot, adopts an out-of-group id if it finds one, and mints only when the id exists
     * nowhere it can reach. The sole minter.
     */
    MINTING,

    /**
     * The upload extension. Reads the shared slot and nothing else — it neither adopts nor mints, because it cannot
     * distinguish "this device has no identity yet" from "the app's identity is not reachable from here", and acting
     * on that ambiguity is what produced two identities. The app resolves the identity on every launch, so the
     * extension's wait is bounded.
     */
    READ_ONLY,
}

/** One device-identity resolution. */
sealed interface DeviceIdResult {

    /** The id, and how this resolution came by it. */
    data class Id(val value: String, val via: Via) : DeviceIdResult

    /** "I could not look" — a store read or the persisting write failed. Never minted, never used unsaved. */
    data class Unavailable(val detail: String) : DeviceIdResult

    /** The lookup succeeded, found nothing, and this process may not mint ([DeviceIdentityRole.READ_ONLY]). */
    data object AbsentNotMintable : DeviceIdResult

    enum class Via { READ, ADOPTED, MINTED }
}
