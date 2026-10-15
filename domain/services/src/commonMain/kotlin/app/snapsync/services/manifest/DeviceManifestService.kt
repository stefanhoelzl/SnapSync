package app.snapsync.services.manifest

import app.snapsync.data.store.SharedDatabase
import app.snapsync.domain.event.EventId
import app.snapsync.model.UploadedManifest
import app.snapsync.model.deviceManifestFromJson
import app.snapsync.model.runCatchingCancellable
import co.touchlab.kermit.Logger

/**
 * The device manifest's skip record: [DeviceManifestService] over the one
 * `uploadedManifest` row of the shared database, so the app and the extension agree on what was last published.
 *
 * Every non-answer reads as **not believed** — the record is a skip optimisation, and "no record" only costs a
 * republish — so an unreadable record is `null`, a failed write leaves the old one (logged), and a failed clear leaves
 * the record believed (logged): the manifest then republishes, which is the safe direction.
 *
 * A record never moves to an older version of its event (`recordUploaded`): two publishes crossing in the network end
 * with the newer recorded, as the backend keeps the newer.
 */
class DeviceManifestService(
    private val db: SharedDatabase,
    private val log: Logger = Logger.withTag("deviceManifest"),
) {

    /**
     * Absence: null covers "nothing uploaded yet" and "could not read the record" alike. Both skip the skip-if-unchanged
     * optimisation and re-write the manifest — an idempotent PUT — so the collapse costs one redundant upload of a small
     * JSON and never a wrong belief. (The dangerous direction here is the opposite one, a STALE non-null: that once
     * suppressed the rewrite forever. Staleness is outside this law; see `LeaveEvent`'s note.)
     */
    fun loadLastUploaded(): UploadedManifest? = runCatchingCancellable {
        db.use { it.uploadedManifestQueries.selectUploaded(::UploadedManifest).executeAsOneOrNull() }
    }.getOrElse { null.also { _ -> log.w(it) { "the manifest record is unreadable — not believed" } } }

    fun saveLastUploaded(record: UploadedManifest) {
        runCatchingCancellable {
            db.use { it.uploadedManifestQueries.recordUploaded(record.eventId, record.version, record.manifest) }
        }.onFailure { log.w(it) { "the manifest record was not written" } }
    }

    /**
     * How many photos the record declares for [eventId] — what this device last told the backend it shares there
     * (change `count-declared-photos`). `0` when the record is another event's or there is none: nothing of this
     * device's is in that event's declared total. `null` when it cannot be read or decoded.
     */
    fun publishedCount(eventId: EventId): Int? {
        val record = runCatchingCancellable {
            db.use { it.uploadedManifestQueries.selectUploaded(::UploadedManifest).executeAsOneOrNull() }
        }.getOrElse { return null.also { _ -> log.w(it) { "the manifest record is unreadable — no own count" } } }
        if (record == null || record.eventId != eventId) return 0
        return runCatchingCancellable { deviceManifestFromJson(record.manifest).assets.size }
            .getOrElse { null.also { _ -> log.w(it) { "the manifest record does not decode — no own count" } } }
    }

    /** Forgets the record: absent and "not believed" are the same state, which [loadLastUploaded] reads. */
    fun clearLastUploaded() {
        runCatchingCancellable { db.use { it.uploadedManifestQueries.clearUploaded() } }
            .onFailure { log.w(it) { "clearLastUploaded: the record stays, so the manifest is still believed" } }
    }
}
