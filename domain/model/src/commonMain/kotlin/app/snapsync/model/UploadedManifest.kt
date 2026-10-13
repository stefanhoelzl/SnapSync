package app.snapsync.model

import app.snapsync.domain.event.EventId

/**
 * The device manifest the backend last accepted: its event, the manifest version it
 * carried, and its JSON — the skip record a cycle compares a fresh projection against before publishing.
 */
data class UploadedManifest(val eventId: EventId, val version: Long, val manifest: String)
