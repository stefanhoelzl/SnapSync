package app.snapsync.feature.upload

import app.snapsync.model.AssetId
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.ConfigRead
import app.snapsync.model.DeviceIdentityAbsent
import app.snapsync.model.EventConfig
import app.snapsync.model.PauseReason.LEGACY_RECORDS
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.model.SharedRecordsReadiness
import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.selectionPolicyFor
import app.snapsync.services.config.ConfigService
import app.snapsync.services.crypto.EventKeys
import app.snapsync.services.databases.SharedDatabase
import app.snapsync.services.downloads.RecognitionSource
import app.snapsync.services.downloads.SuppressionSource
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.services.ledger.LedgerService

/**
 * THE ENTRY-GATE TRANSLATION — the upload cycle owns its entry decision — one
 * implementation over the services both uploaders build, where three per-root copies used to live. It is **port-pure**:
 * one fresh [ConfigService.read] per cycle, the identity probe, the host read, and the process's [admission] answer —
 * and deliberately nothing else.
 *
 * It does not refresh the UI-facing membership `StateFlow`: the app-driven tier's copy once did, and the extension's
 * semantics won (decision record `changes/archive/2026-07-17-establish-shared-composition`, D1).
 */
class CycleGateRead(
    /** Whether the shared records can be read here — the extension pauses until the app has copied their legacy homes. */
    private val records: SharedDatabase,
    private val ledger: LedgerService,
    private val config: ConfigService,
    private val identity: PersistedDeviceIdentity,
    private val suppression: SuppressionSource,
    /** Whether the joined membership's received photos have been recognised — read after the suppression store is ready. */
    private val recognition: RecognitionSource,
    /** The build-time upload host; blank when the build carries none, which the gate treats as "cannot upload". */
    private val host: String,
    /** Whether THIS process may create now — each uploader states its own, read once per gate. */
    private val admission: () -> UploadAdmission,
    /** The joined event's key — a LOST one withholds the cycle (see [admissionFor]). */
    private val eventKeys: EventKeys,
    /** The membership's denylisted-album members since a cutoff, answered per the uploader's tier. */
    private val albumExclusions: suspend (CaptureCutoff) -> Set<AssetId>,
) {
    /**
     * The gate. The legacy-records check is first; the suppression reads are last, and only for an admitted cycle: the
     * extension opens the download store read-only there, so a process that may not create never opens it. A store
     * that is ready then answers whether the membership's received photos have been recognised ([recognitionGate]).
     */
    suspend fun read(): CycleGate {
        // FIRST, before any record is read: a record still in its legacy home is not in the database yet, and an empty
        // table there would read as "not joined". An unreadable database falls through, to be skipped below.
        if (records.readiness() == SharedRecordsReadiness.LegacyPending) return CycleGate.Paused(LEGACY_RECORDS)
        val gate = entryGate()
        if (gate !is CycleGate.Run) return gate
        val ready = suppressionGate(gate, suppression.readiness())
        if (ready !is CycleGate.Run) return ready
        return recognitionGate(ready, recognition.isRecognised(ready.membership.eventId))
    }

    /** The gate from the membership, the identity and the admission — everything but the suppression read. */
    private suspend fun entryGate(): CycleGate {
        // The manifest version FIRST — before the membership, and so before the policy and the rows the manifest
        // is projected from. Every change that could alter the projection
        // advances it, so a change this cycle's projection misses happened after this read and carries a higher
        // version. Unreadable (a locked device's protected ledger) is "I could not look", like the config.
        val version = runCatchingCancellable { ledger.manifestVersion() }
        val read = config.read()
        // The identity probe — an unresolvable id is "I could not look", never "no id", so it belongs on the
        // unreadable side of the roll-up. Every outcome needs the id: the reconciler and the manifest producer each
        // close over it, so even the leave-side branch touches it.
        //
        // `DeviceIdentityAbsent` joins `SecureStoreUnavailable` here, and the two are handled identically on purpose.
        // It means the lookup succeeded, found nothing, and this process may not mint (the upload extension).
        // Both are "proceed with no identity", and proceeding is exactly what must not
        // happen: an invented id partitions this device's bytes away from its own manifest. Anything else still
        // propagates — a genuine fault must not be silently downgraded to a skipped cycle.
        val identityFailure = runCatchingCancellable { identity.deviceId() }
            .onFailure { if (it !is SecureStoreUnavailable && it !is DeviceIdentityAbsent) throw it }
            .exceptionOrNull()
        val payload = (read as? ConfigRead.Joined)?.config
        return cycleGate(
            configReadable = read !is ConfigRead.Unavailable && identityFailure == null && version.isSuccess,
            membership = payload?.let {
                JoinedMembership(
                    eventId = it.eventId,
                    // A supplier, not a value: the derivation reads two ports and this translation must stay
                    // port-pure. Closing over them is not calling them.
                    policy = {
                        selectionPolicyFor(
                            config = it,
                            // Read when the cycle derives its policy, not at the gate: a cycle that crosses the
                            // event's start shares what the start admits from then on.
                            now = config.now(),
                            suppressedAssetIds = { suppression.suppressedLocalIds() },
                            albumExcludedAssetIds = albumExclusions,
                        )
                    },
                    saveToAlbum = it.saveToAlbum,
                    manifestVersion = version.getOrDefault(0L),
                )
            },
            host = host,
            admission = admissionFor(payload),
            // The forensics for a skip: the decision is made in shared code that cannot see WHY the read failed, and an
            // unreadable config is invisible on a device except through this string.
            skipDetail = skipDetail(read, identityFailure, version.exceptionOrNull()),
        )
    }

    /**
     * Whether this process may create now. Each root states its own answer — the app from resolution, the extension
     * from its own grant read. A LOST event key withholds in both: nothing is walked, staged, sealed or published until
     * the invite brings it back, where an unreadable one still runs and withholds at each
     * seal. So does an event whose photos are deleted: it takes no more.
     */
    private fun admissionFor(membership: EventConfig?): UploadAdmission =
        if (membership?.deleted == true || eventKeys.lostFor(membership)) UploadAdmission.Withheld else admission()
}

/** The skip line [CycleGateRead] hands the cycle: which read failed, and how. */
internal fun skipDetail(read: ConfigRead, identityFailure: Throwable?, versionFailure: Throwable?): String =
    "protected data unavailable (config: " +
        "${(read as? ConfigRead.Unavailable)?.detail}, deviceId readable=${identityFailure == null}" +
        // Naming WHICH identity failure occurred is the difference between "the device is locked, this will pass" and
        // "this process has no identity and may not create one", which need opposite reactions from whoever reads the
        // log.
        when (identityFailure) {
            is DeviceIdentityAbsent -> ", deviceId absent and unmintable here"
            is SecureStoreUnavailable -> ", deviceId unreadable (${identityFailure.detail})"
            else -> ""
        } +
        (if (versionFailure != null) ", manifest version unreadable ($versionFailure)" else "") + ")"
