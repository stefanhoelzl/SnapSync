package app.snapsync.domain.device

import app.snapsync.domain.event.Uuids
import kotlin.jvm.JvmInline

/**
 * This install's identity: the byte partition `/files/devices/<deviceId>/`, the manifest key, the identity every
 * backend call is made under. A canonical UUID (`8-4-4-4-12` hex, either case), as the backend declares every device
 * id (`api/openapi.yaml`, `Uuid`), kept **exactly as it arrived** — an id minted on iOS is upper-case
 * (`NSUUID().UUIDString`), and the backend's storage paths are case-sensitive. [parse] is the one way in.
 *
 * It renders in full: crash reports may carry device, event and photo ids (`docs/patterns.md`, "Secret types").
 */
@JvmInline
value class DeviceId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        /** The device id [text] spells, or `null` when it is not a canonical UUID. */
        fun parse(text: String): DeviceId? = if (Uuids.isCanonical(text)) DeviceId(text) else null
    }
}
