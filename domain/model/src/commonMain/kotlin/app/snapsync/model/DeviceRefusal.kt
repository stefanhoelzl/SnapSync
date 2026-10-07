package app.snapsync.model

import kotlinx.serialization.Serializable

/**
 * Why the service refused this phone as not genuine, as the user is told it (capability `privacy-security`, "A refused
 * phone is told why"). A CLOSED set, coarser than the backend's checks on purpose: the precise check stays in its log.
 *
 * Read off v2's `401 attestation rejected: <reason>` by [wireName]. A refusal that names no reason — v1's bare body, or
 * one this build does not know — is [DEVICE_UNVERIFIABLE], the one reason that never accuses the user.
 */
@Serializable
enum class DeviceRefusal(val wireName: String) {
    /** An unlocked bootloader, a system its maker did not ship, or a key held only in software. */
    DEVICE_MODIFIED("device-modified"),

    /** The proof the phone's hardware gives is not one the service recognises — and the default. */
    DEVICE_UNVERIFIABLE("device-unverifiable"),

    /** This copy of the app is not the official one: another package, or another signing certificate. */
    APP_NOT_GENUINE("app-not-genuine"),
    ;

    companion object {
        /** The reason [name] names on the wire; anything else — absent or unknown — is [DEVICE_UNVERIFIABLE]. */
        fun fromWire(name: String?): DeviceRefusal = entries.firstOrNull { it.wireName == name?.trim() } ?: DEVICE_UNVERIFIABLE
    }
}

/**
 * What the app knows about the latest refused attestation, for a report the user sends from "Report this" (capability
 * `privacy-security`): the service's [reason] and diagnostic [detail] as it named them, and the [chain] the phone
 * presented, where the platform exposes it. Held in memory only.
 */
data class RefusalFacts(val reason: DeviceRefusal, val detail: String?, val chain: AttestationChain?)
