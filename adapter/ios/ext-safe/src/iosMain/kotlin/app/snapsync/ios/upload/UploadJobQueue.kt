package app.snapsync.ios.upload

import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.LedgerStore
import co.touchlab.kermit.Logger

/**
 * **Which implementation of the OS upload-job subsystem this target binds** (capability
 * `ios-photokit-upload`, "The upload-job subsystem binding is fixed by the compilation target").
 *
 * The subsystem is the OS-owned job queue — fetch, create, retry, acknowledge — and, beside it, the
 * registration record the app toggles. `iosArm64`, every shipped binary, binds
 * [IosPhotoKitUploadPlatform] and only that. `iosSimulatorArm64` binds a substitute, because on that host
 * the subsystem cannot run at all.
 *
 * ## Why a target and not a runtime check
 *
 * The same reasoning as [app.snapsync.keychain.deviceIdPrimaryStore], and here the stakes are higher.
 * A simulator does not merely fail to schedule the extension: `setUploadJobExtensionEnabled(true)` is
 * refused with `PHPhotosErrorDomain:-1` (measured 2026-08-26, iOS 26.5, full grant, clean device,
 * extension embedded and signed), and with no configuration record
 * `creationRequestForJobWithDestination` raises `NSInvalidArgumentException` from inside
 * `-[PHAssetResourceUploadJobChangeRequest setUploadJobConfiguration:]` and **terminates the process**.
 * It does not return an error. So a runtime branch that could be taken wrongly would not degrade — it
 * would kill the app under test, with a stack naming Apple's frames rather than ours.
 *
 * `iosSimulatorArm64` is not a guess about the host: it is a compilation target whose output only ever
 * runs on a simulator, so a device binary contains **no route** to the substitute — *"contained by
 * compilation, not by a runtime check"* (spec `module-architecture`). Because a simulator refuses every
 * provisionable entitlement, ad-hoc signing with the App Group alone is the only buildable configuration
 * for that target, so the measurement is co-extensive with the target rather than with one signing form.
 *
 * ⏰ **Expiry:** re-measure at the next iOS major, alongside the other PhotoKit platform facts.
 *
 * ## What is NOT bound here
 *
 * Only the job subsystem. Asset and resource fetches, the persistent change-token walk, the selection
 * policy's reads, and album creation and membership are the real platform APIs on every target — they
 * work on a simulator, and they are among the most valuable things that host exercises. A substitute
 * therefore **delegates discovery** to the same [IosDiscovery] the PhotoKit implementation delegates to,
 * rather than answering it itself.
 *
 * Decision record: `changes/exercise-os-driven-upload-on-simulator` (D6, D7).
 */
expect fun uploadJobQueue(
    log: Logger,
    discovery: IosDiscovery,
    ledger: LedgerStore,
): BackgroundTransfer
