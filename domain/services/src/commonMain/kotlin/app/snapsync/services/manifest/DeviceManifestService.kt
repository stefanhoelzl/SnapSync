package app.snapsync.services.manifest

import app.snapsync.model.UploadedManifest
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.databases.SharedDatabase
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

    /** Forgets the record: absent and "not believed" are the same state, which [loadLastUploaded] reads. */
    fun clearLastUploaded() {
        runCatchingCancellable { db.use { it.uploadedManifestQueries.clearUploaded() } }
            .onFailure { log.w(it) { "clearLastUploaded: the record stays, so the manifest is still believed" } }
    }
}
