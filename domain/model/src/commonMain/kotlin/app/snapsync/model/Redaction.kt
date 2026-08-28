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

/**
 * The tag an outgoing report sets to declare itself **exempt from redaction** (capability
 * `crash-reporting`; the exemption's contract lives in `diagnostic-logging`).
 *
 * Named for the property it claims, not for the one feature that claims it: today only the
 * operator-initiated diagnostic dump is exempt, and the name should not have to change if that ever
 * stops being true.
 */
const val NON_REDACTED_TAG: String = "non-redacted"

/**
 * Whether an outgoing event carrying [tags] must be redacted (capability `crash-reporting`).
 *
 * The exemption is carried **by the event** rather than by where its payload sits. That is the whole
 * point: the diagnostic dump used to survive the scrub only because the scrub reached message text
 * and not structured context sections — an incidental property that any well-meaning widening of the
 * scrub would have destroyed silently, emptying every future dump with no failing test and no visible
 * error. An event that declares itself exempt is skipped whatever the scrub covers.
 *
 * Both halves of this are pinned by tests (`diagnostic-logging`): that the sender **sets** the tag,
 * and that the scrubbing step **consults** this predicate. Either half missing degrades every future
 * dump silently.
 */
fun redactsMessages(tags: Map<String, String>): Boolean = tags[NON_REDACTED_TAG] != "1"

private val UUID_SHAPED = Regex(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
)
