package app.snapsync.model

/**
 * Redacts every UUID-shaped token in [text] (capability `crash-reporting`).
 *
 * One content-blind rule, on purpose: an eventId IS the upload capability, the device id is the
 * GDPR-request correlator, and future log lines will interpolate identifiers nobody audits — so the
 * crash-reporting channel scrubs every UUID rather than trying to know which UUIDs are which. The
 * SDK-generated per-install `user.id` is the deliberate structured-field exception (see the spec);
 * it never passes through here because it is not message text.
 */
fun redactUuids(text: String): String = UUID_SHAPED.replace(text, REDACTED_UUID)

const val REDACTED_UUID: String = "‹uuid›"

private val UUID_SHAPED = Regex(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
)
