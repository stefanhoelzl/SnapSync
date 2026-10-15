package app.snapsync.compose

import app.snapsync.data.identity.MintingDeviceIdentity
import app.snapsync.data.membership.EventCheckRecord
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.PendingLeaves
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.time.WallClock
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.data.trust.SecureCredentialStore
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.crypto.EventKeys
import app.snapsync.services.crypto.FileCipher
import app.snapsync.services.databases.LegacyRecords
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.logs.LogTailService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.membership.MembershipProjection
import app.snapsync.services.membership.MembershipTransition
import app.snapsync.services.push.PushRegistrationRecord
import app.snapsync.services.push.PushTokenSource
import app.snapsync.services.settings.MobileDataSetting
import app.snapsync.services.staging.StagingService
import co.touchlab.kermit.Logger

/**
 * **The app process's services**, built here over [ports] and the [process] — never by a root (`docs/architecture.md`,
 * "One shared composition"). A root hands its composition ports and nothing else, so what a store holds, when it opens
 * and which role an identity plays is decided once, here, for every root: the phone's, the JVM's and the rig's.
 *
 * Every member is `by lazy`: nothing opens a database, reads the Keychain or resolves the device identity at
 * composition, so a locked background launch composes as before. The app's own uploader reads these same instances
 * (`appUploader`), so the ledger, the membership and the manifest record it writes are the ones this graph reads.
 */
internal class AppServices(val ports: AppPorts, val process: ProcessServices) {

    /** The composition's own log lines, over this process's writers. */
    val log: Logger = process.logger("app")

    /**
     * The membership — every trigger re-reads it before acting ([MembershipRepository.reload]), because cross-process
     * writes and a pre-first-unlock seed never notify this process's state flow.
     */
    val membership: MembershipRepository by lazy { MembershipRepository(sharedDatabase) }

    /** The membership as the old zones read it — MIGRATING ([MembershipProjection]). */
    val config: MembershipProjection by lazy { MembershipProjection(membership, process.clock) }

    /** The device's wall clock, as the layers read time. */
    val wallClock: WallClock by lazy { WallClock(process.clock) }

    /** A join in progress, from its key to its membership — what a sweep of a failed leave's leftovers waits out. */
    val transition: MembershipTransition = MembershipTransition()

    /** The leaves the backend has not confirmed. */
    val pendingLeaves: PendingLeaves by lazy { PendingLeaves(sharedDatabase) }

    /**
     * The shared database — `ledger.db`, which the extension opens too — holding the upload rows and the small records
     * both processes share. The app copies the records' legacy homes into it at its first
     * open ([LegacyRecords]; a shim, `PENDING_CLEANUPS.md`).
     */
    val sharedDatabase: SharedDatabase by lazy {
        SharedDatabase(ports.databases, LegacyRecords(process.files, ports.preferences, log))
    }

    /**
     * What this process knows about its own uploads: the app **reads** it — the status
     * counts and the dump — and **resets** it at membership transitions; the app's uploader records through its own
     * `LedgerWriter` over it. On iOS ≥26.1 the extension writes the same App-Group ledger from its own process. Scoped
     * to the event [config] says this process is joined to.
     */
    val ledger: LedgerService by lazy { LedgerService(sharedDatabase) { config.config.value?.eventId } }

    /** The download store — the app is its one writer and its one migrator. */
    val downloadStore: DownloadService by lazy { DownloadService(ports.databases) }

    /**
     * Where downloaded bytes are staged, and who releases them once their row settles — one service owns both
     * halves, so the two never name different directories.
     */
    val stagedBytes: StagingService by lazy { StagingService(process.files) }

    /**
     * The device manifest's skip record, in the shared database — the SAME record the ≥26.1 extension's producer reads.
     * A join writes no manifest; its share-set load advances the manifest version, so the producer republishes under
     * the newer one.
     */
    val manifestStore: DeviceManifestService by lazy { DeviceManifestService(sharedDatabase) }

    /**
     * The attestation token and key id, in the shared store the extension reads — held in memory between re-reads by
     * this process's one copy, which the credential and the uploader share.
     */
    val credentials: CachedCredentialStore by lazy { CachedCredentialStore(SecureCredentialStore(ports.secureStore)) }

    /**
     * The device identity. Minting is the app's alone: it also adopts an id an older build wrote, rather than
     * re-minting a second identity that would orphan this device's byte partition. It keeps only a success, so a
     * resolve that fails on a locked device is retried on the next call.
     */
    @OptIn(AppProcessOnly::class)
    val deviceIdentity: MintingDeviceIdentity by lazy {
        MintingDeviceIdentity(ports.secureStore, ports.platformDeviceId)
    }

    /**
     * The joined event's key, when it is encrypted (the encrypted file format, `docs/architecture.md`), in the shared
     * slot the extension reads too.
     */
    val eventKeys: EventKeys by lazy { EventKeys(process.crypto, ports.secureStore) }

    /** An encrypted event's files, sealed and opened a segment at a time over this process's files. */
    val fileCipher: FileCipher by lazy { FileCipher(process.crypto, process.files) }

    /** The event album's leave-surviving `eventId → album` map. */
    val albumMapStore: AlbumMapService by lazy { AlbumMapService(sharedDatabase) }

    /**
     * The device's mobile-data choice (decision record `changes/archive/2026-10-07-mobile-data-per-device`, D1) —
     * over the shared preferences, which the upload extension reads too.
     */
    val mobileData: MobileDataSetting by lazy { MobileDataSetting(ports.preferences) }

    /**
     * When a background wake last asked the event for its photos and its state (decision record
     * `changes/timely-background-receiving`, D4–D5) — in the shared database, so it outlives the process a wake usually
     * is.
     */
    val eventChecks: EventCheckRecord by lazy { EventCheckRecord(sharedDatabase) }

    /**
     * The OS-delivered push token, paired with the push service's kind (its adapter's)
     * and this build's push environment — fed by the push service's `onToken`, which the host zone registers as the
     * graph is composed.
     */
    val pushTokens: PushTokenSource by lazy {
        PushTokenSource(ports.pushNotifications.kind, ports.process.build.pushEnvironment)
    }

    /**
     * The last push registration the backend accepted, against which a delivered token is compared — so a launch that
     * delivers the unchanged token publishes nothing.
     */
    val pushRecord: PushRegistrationRecord by lazy { PushRegistrationRecord(ports.preferences, process.files) }

    /**
     * The device logs a dump reads back, over the process's files: an off-device

     * composition holds no log files, so a dump assembled there is honestly empty rather than fabricated.
     */
    val deviceLogs: LogTailService by lazy { LogTailService(process.files) }
}
