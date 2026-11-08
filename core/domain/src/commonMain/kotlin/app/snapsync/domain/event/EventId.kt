package app.snapsync.domain.event

import kotlin.jvm.JvmInline

/**
 * An event's identity: a canonical UUID (`8-4-4-4-12` hex, either case), as the backend declares every event id
 * (`api/openapi.yaml`, `Uuid`). It is kept **exactly as it arrived** — never normalised: the backend's storage paths
 * are case-sensitive, so a re-cased id would name another event. [parse] is the one way in, so an id that exists is
 * an id the backend could have issued.
 *
 * It renders in full: crash reports may carry device, event and photo ids — never an event key
 * (`docs/patterns.md`, "Secret types").
 */
@JvmInline
value class EventId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        /** The event id [text] spells, or `null` when it is not a canonical UUID. */
        fun parse(text: String): EventId? = if (Uuids.isCanonical(text)) EventId(text) else null
    }
}
