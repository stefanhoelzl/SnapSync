import Photos
import SnapSyncUploadKit

/// Principal class for the PhotoKit background-upload extension.
///
/// All logic lives in Kotlin (`SnapSyncUploadKit` → `UploadExtensionRoot`); this Swift shell only
/// conforms to the system protocol and forwards. The system calls `process()` when it is time to
/// handle uploads; we run one discover → engine → drain cycle (blocking, in Kotlin) and construct
/// the system result from the raw value Kotlin decided.
///
/// THE ONE REMAINING SWIFT PIN (SwiftShellGuardTest; settled forcing proof ① of migration step 12):
/// `PHBackgroundResourceUploadProcessingResult` is **Swift-only** — declared in the SDK's
/// swiftinterface with no ObjC header — so Kotlin cannot construct it and the construction cannot
/// leave this file. But it is RawRepresentable over Int, so the DECISION lives in Kotlin:
/// `processRawValue()` returns the tested `CycleResult → raw Int` mapping (exhaustive, compiler
/// checked, pinned in commonTest), and this shell forwards it into `init?(rawValue:)` verbatim. The
/// `?? .failure` is the nil fallback for a raw value the SDK enum does not carry — the same
/// visible-retry posture the previous `switch`'s `default:` arm had: an untaught value surfaces as
/// a retried, logged failure, never a silently "successful" cycle.
///
/// Verified on device (real-s3-upload, build 70): the `@main` ExtensionKit conformance, the
/// synchronous `process()`, and the `.completed` / `.failure` / `.processing` result cases all work
/// against the iOS 26.1 `PHBackgroundResourceUploadExtension`. iOS 27 replaces this with the async
/// `PHBackgroundResourceUploadJobExtension` (`processJobs() async` + `willTerminate()`); because all
/// logic is Kotlin, that migration is confined to this shell and the deployment target.
@main
final class BackgroundUploadExtension: PHBackgroundResourceUploadExtension {

    required init() {}

    func process() -> PHBackgroundResourceUploadProcessingResult {
        PHBackgroundResourceUploadProcessingResult(
            rawValue: Int(UploadExtensionRoot.shared.processRawValue())
        ) ?? .failure
    }

    func notifyTermination() {
        // v1: the Kotlin cycle is synchronous (runBlocking), so there is nothing in flight to
        // interrupt or persist here.
    }
}
