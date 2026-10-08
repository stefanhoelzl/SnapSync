package app.snapsync.feature.creation.readmodel

/**
 * An event this device just minted (capability `create-event`), on its way to the join gate a scanned QR takes —
 * [linkKey] is the invite key an encrypted event carries, `null` for a plain one.
 */
class MintedEvent(val eventId: String, val linkKey: String?)
