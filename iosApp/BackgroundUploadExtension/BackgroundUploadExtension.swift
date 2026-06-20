import Photos
import SnapSyncUploadKit

/// Principal class for the PhotoKit background-upload extension.
///
/// All logic lives in Kotlin (`SnapSyncUploadKit` → `UploadExtensionRoot`); this Swift shell only
/// conforms to the system protocol and forwards. The system calls `process()` when it is time to
/// handle uploads; we run one discover → engine → dummy-job → drain cycle (blocking, in Kotlin) and
/// map the boolean result to the system's processing result.
///
/// ⚠️ VERIFY against the installed iOS 26.1 SDK when wiring the Xcode target (this file is a draft —
/// it has never been compiled on this machine; there is no Swift toolchain here):
///   * Protocol: `PHBackgroundResourceUploadExtension` is the iOS 26.1 (deprecated) type; iOS 27
///     replaces it with the async `PHBackgroundResourceUploadJobExtension` (`processJobs() async`).
///   * Whether `process()` is synchronous (as written) or takes a completion handler / is `async`.
///   * Whether conformance is declared with `@main` (ExtensionKit `AppExtension`, as written) or via
///     a classic `NSExtensionPrincipalClass` in Info.plist — adding the target through Xcode's
///     extension template generates the canonical principal-class wiring and Info.plist for you.
///   * `PHBackgroundResourceUploadProcessingResult` case names (`.completed` / `.failure` /
///     `.processing`).
@main
final class BackgroundUploadExtension: PHBackgroundResourceUploadExtension {

    required init() {}

    func process() -> PHBackgroundResourceUploadProcessingResult {
        // Map the Kotlin tri-state cycle result to the system result. The Kotlin enum entries
        // COMPLETED/PROCESSING/FAILED export to Swift as .completed/.processing/.failed.
        switch UploadExtensionRoot.shared.process() {
        case .processing:
            // The in-flight cap was hit — ask the system to run us again. ⚠️ VERIFY `.processing`
            // exists on the iOS 26.1 `PHBackgroundResourceUploadProcessingResult`; if it does not,
            // return `.completed` here — the un-advanced cursor still drains the remainder on the
            // next system-scheduled wake (only promptness is lost).
            return .processing
        case .failed:
            return .failure
        default:
            return .completed
        }
    }

    func notifyTermination() {
        // v1: the Kotlin cycle is synchronous (runBlocking), so there is nothing in flight to
        // interrupt or persist here.
    }
}
