package app.snapsync.composition.app

import app.snapsync.composition.process.ProcessGraph
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.crypto.FileCipher
import app.snapsync.data.identity.MintingDeviceIdentity
import app.snapsync.data.legacy.LegacyRecords
import app.snapsync.data.membership.EventCheckRecord
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.PendingLeaves
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.push.PushRegistrationRecord
import app.snapsync.data.push.PushTokens
import app.snapsync.data.sharing.ManifestRecord
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.time.WallClock
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.data.trust.SecureCredentialStore
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.scheduling.Exclusion

/**
 * **The app process's services**, built over [ports] and the [process] — never by a root (`docs/architecture.md`, "The
 * composition layer"). Nothing here opens a database, reads the Keychain or resolves the device identity as it is
 * built — every store opens on first use — so a locked background launch composes as before. The app's own uploader
 * reads these same instances, so the ledger, the membership and the manifest record it writes are the ones this graph
 * reads.
 */
@OptIn(AppProcessOnly::class)
class ServicesGraph(ports: AppPorts, process: ProcessGraph) {
    /**
     * The shared database — `ledger.db`, which the extension opens too — holding the upload rows and the small records
     * both processes share. The app copies the records' legacy homes into it at its first open ([LegacyRecords]; a
     * shim, `PENDING_CLEANUPS.md`).
     */
    val sharedDatabase: SharedDatabase =
        SharedDatabase(ports.databases, LegacyRecords(process.files, ports.preferences))

    /**
     * The membership — every trigger re-reads it before acting ([MembershipRepository.reload]), because cross-process
     * writes and a pre-first-unlock seed never notify this process's state flow.
     */
    val membership: MembershipRepository = MembershipRepository(sharedDatabase)

    /** The device's wall clock, as the layers read time. */
    val wallClock: WallClock = WallClock(process.clock)

    /** The membership change a join holds from its key on — what a sweep of a failed leave's leftovers yields to. */
    val membershipChange: Exclusion = Exclusion("membership change")

    /** The leaves the backend has not confirmed. */
    val pendingLeaves: PendingLeaves = PendingLeaves(sharedDatabase)

    /** The device manifest's skip record, in the shared database — the SAME record the extension's producer reads. */
    val manifestRecord: ManifestRecord = ManifestRecord(sharedDatabase)

    /**
     * The attestation token and key id, in the shared store the extension reads — held in memory between re-reads by
     * this process's one copy, which the credential and the uploader share.
     */
    val credentials: CachedCredentialStore = CachedCredentialStore(SecureCredentialStore(ports.secureStore))

    /**
     * The device identity. Minting is the app's alone: it also adopts an id an older build wrote, rather than
     * re-minting a second identity that would orphan this device's byte partition.
     */
    val deviceIdentity: MintingDeviceIdentity = MintingDeviceIdentity(ports.secureStore, ports.platformDeviceId)

    /** An event key's id, and a fresh key (the encrypted file format, `docs/architecture.md`). */
    val eventKeyIds: EventKeyIds = EventKeyIds(process.crypto)

    /** The joined event's key, when it is encrypted, in the shared slot the extension reads too. */
    val eventKeyStore: EventKeyStore = EventKeyStore(ports.secureStore, eventKeyIds)

    /** An encrypted event's files, sealed and opened a segment at a time over this process's files. */
    val fileCipher: FileCipher = FileCipher(process.crypto, process.files)

    /** The device's mobile-data choice — over the shared preferences, which the upload extension reads too. */
    val mobileData: MobileDataStore = MobileDataStore(ports.preferences)

    /** When a background wake last asked the event for its photos and its state — in the shared database. */
    val eventChecks: EventCheckRecord = EventCheckRecord(sharedDatabase)

    /** The OS-delivered push token, paired with the push service's kind and this build's push environment. */
    val pushTokens: PushTokens = PushTokens(ports.pushNotifications, ports.process.build, deviceIdentity)

    /** The last push registration the backend accepted, against which a delivered token is compared. */
    val pushRecord: PushRegistrationRecord = PushRegistrationRecord(ports.preferences, process.files)
}
