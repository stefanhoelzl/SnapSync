package app.snapsync.compose

import app.snapsync.services.crypto.EventKeys
import app.snapsync.services.crypto.FileCipher
import app.snapsync.services.settings.MobileDataSetting
import app.snapsync.services.wake.EventChecks
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.ports.AttestStore
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.config.ConfigService
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.identity.AttestState
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.logs.LogTailService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.push.PushRegistrationRecord
import app.snapsync.services.push.PushTokenSource
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
     * The membership (capability `join-event`): read, saved, cleared and re-read through this one service — every
     * trigger flow re-reads it before acting (`ConfigService.reload`), because cross-process writes and a
     * pre-first-unlock seed never notify this process's state flow.
     */
    val config: ConfigService by lazy { ConfigService(process.files, process.clock) }

    /**
     * What this process knows about its own uploads (capability `photo-sharing`): the app **reads** it — the status
     * counts and the dump — and **resets** it at membership transitions; the app's uploader records through its own
     * `LedgerWriter` over it. On iOS ≥26.1 the extension writes the same App-Group ledger from its own process. Scoped
     * to the event [config] says this process is joined to.
     */
    val ledger: LedgerService by lazy { LedgerService(ports.databases) { config.config.value?.eventId } }

    /** The download store — the app is its one writer and its one migrator (capability `receiving-photos`). */
    val downloadStore: DownloadService by lazy { DownloadService(ports.databases) }

    /**
     * Where downloaded bytes are staged, and who releases them once their row settles (capability
     * `receiving-photos`) — one service owns both halves, so the two never name different directories.
     */
    val stagedBytes: StagingService by lazy { StagingService(process.files) }

    /**
     * The device manifest's skip record, in the shared area — the SAME record the ≥26.1 extension's producer reads.
     * Enrolling overwrites the server's manifest with an empty one, so it must invalidate this record or the producer
     * skips the rewrite (see [app.snapsync.feature.membership.ManifestDeviceEnroller]).
     */
    val manifestStore: DeviceManifestService by lazy { DeviceManifestService(process.files) }

    /** The attestation token and key id (capability `privacy-security`), in the shared store the extension reads. */
    val attestStore: AttestStore by lazy { AttestState(ports.secureStore) }

    /**
     * The device identity. MINTING is the app's role alone (capability `photo-sharing`): it also adopts an id an older
     * build wrote, rather than re-minting a second identity that would orphan this device's byte partition. Read per
     * use, and it keeps only a success, so a resolve that fails on a locked device is retried on the next call.
     */
    val deviceIdentity: PersistedDeviceIdentity by lazy {
        PersistedDeviceIdentity(DeviceIdentityRole.MINTING, ports.secureStore, ports.platformDeviceId)
    }

    /**
     * The joined event's key, when it is encrypted (the encrypted file format, `docs/architecture.md`), in the shared
     * slot the extension reads too.
     */
    val eventKeys: EventKeys by lazy { EventKeys(process.crypto, ports.secureStore) }

    /** An encrypted event's files, sealed and opened a segment at a time over this process's files. */
    val fileCipher: FileCipher by lazy { FileCipher(process.crypto, process.files) }

    /** The event album's leave-surviving `eventId → album` map (capability `event-album`). */
    val albumMapStore: AlbumMapService by lazy { AlbumMapService(ports.preferences, ports.secureStore) }

    /**
     * The device's mobile-data choice (capability `mobile-data`; decision record
     * `changes/archive/2026-10-07-mobile-data-per-device`, D1) — over the shared preferences, which the upload
     * extension reads too.
     */
    val mobileData: MobileDataSetting by lazy { MobileDataSetting(ports.preferences) }

    /**
     * When a background wake last asked the event for its photos and its state (capability `receiving-photos`;
     * decision record `changes/timely-background-receiving`, D4–D5) — over the shared preferences, so it outlives the
     * process a wake usually is.
     */
    val eventChecks: EventChecks by lazy { EventChecks(ports.preferences, now = process.clock::now) }

    /**
     * The OS-delivered push token (capability `receiving-photos`), paired with the push service's kind (its adapter's)
     * and this build's push environment — fed by the push service's `onToken`, which the host zone registers as the
     * graph is composed.
     */
    val pushTokens: PushTokenSource by lazy {
        PushTokenSource(ports.pushNotifications.kind, ports.process.build.apnsEnvironment)
    }

    /**
     * The last push registration the backend accepted, against which a delivered token is compared — so a launch that
     * delivers the unchanged token publishes nothing (capability `receiving-photos`).
     */
    val pushRecord: PushRegistrationRecord by lazy { PushRegistrationRecord(process.files) }

    /**
     * The device logs a dump reads back (capability `privacy-security`), over the process's files: an off-device
     * composition holds no log files, so a dump assembled there is honestly empty rather than fabricated.
     */
    val deviceLogs: LogTailService by lazy { LogTailService(process.files) }
}
