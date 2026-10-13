package app.snapsync.model

import app.snapsync.domain.device.DeviceId

/**
 * Who may create the device identity. Two roles, and only one may mint.
 */
enum class DeviceIdentityRole {

    /**
     * The app. Reads the shared slot, and mints only when the lookup succeeded and found nothing. The sole minter.
     */
    MINTING,

    /**
     * The upload extension. Reads the shared slot and nothing else — it never mints, because it cannot
     * distinguish "this device has no identity yet" from "the app's identity is not reachable from here", and acting
     * on that ambiguity is what produced two identities. The app resolves the identity on every launch, so the
     * extension's wait is bounded.
     */
    READ_ONLY,
}

/** One device-identity resolution. */
sealed interface DeviceIdResult {

    /** The id, and how this resolution came by it. */
    data class Id(val value: DeviceId, val via: Via) : DeviceIdResult

    /** "I could not look" — a store read or the persisting write failed. Never minted, never used unsaved. */
    data class Unavailable(val detail: String) : DeviceIdResult

    /** The lookup succeeded, found nothing, and this process may not mint ([DeviceIdentityRole.READ_ONLY]). */
    data object AbsentNotMintable : DeviceIdResult

    enum class Via { READ, MINTED }
}
