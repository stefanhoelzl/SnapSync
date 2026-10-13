package app.snapsync.domain.trust

import app.snapsync.domain.time.Timestamp

/**
 * The credential every gated backend request carries: the token the backend minted for this device, and the [expiry]
 * it reads off it — `null` for a token whose expiry cannot be read, which cannot be shown to be valid.
 *
 * A SECRET type (`docs/patterns.md`, "Secret types"): it renders masked, so a log line or a crash report that names one
 * carries no credential. Equal by its [secret].
 */
class BearerToken(val secret: String, val expiry: Timestamp?) {
    override fun toString(): String = "BearerToken(•••)"

    override fun equals(other: Any?): Boolean = other is BearerToken && other.secret == secret

    override fun hashCode(): Int = secret.hashCode()
}

/** The handle of the key a device attested with — what a renewal asserts with. Not a secret: it names a key. */
class AttestKeyId(val value: String) {
    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is AttestKeyId && other.value == value

    override fun hashCode(): Int = value.hashCode()
}
