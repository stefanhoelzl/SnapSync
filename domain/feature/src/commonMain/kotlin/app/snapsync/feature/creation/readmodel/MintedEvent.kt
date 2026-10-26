package app.snapsync.feature.creation.readmodel

import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKey

/**
 * An event this device just minted, on its way to the join gate a scanned QR takes —
 * [key] is the key an encrypted event's invite carries, `null` for a plain one.
 */
class MintedEvent(val eventId: EventId, val key: EventKey?)
