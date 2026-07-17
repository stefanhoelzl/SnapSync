import Photos
import SnapSyncUploadKit

/// Principal class for the PhotoKit background-upload extension.
///
/// All logic lives in Kotlin (`SnapSyncUploadKit` → `UploadExtensionRoot`); this Swift shell only
/// conforms to the system protocol and forwards. The system calls `process()` when it is time to
/// handle uploads; we run one discover → engine → dummy-job → drain cycle (blocking, in Kotlin) and
/// map the tri-state result to the system's processing result.
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
        // Map the Kotlin CycleResult to the system result — EVERY case explicit. A Kotlin enum
        // reaches Swift as an ObjC class, so the compiler cannot check exhaustiveness and demands a
        // `default:` — which therefore maps to FAILURE, never success: a future Kotlin case that
        // nobody taught this switch must surface as a retried, logged failure, not silently report
        // a successful upload cycle that never ran (that was the pre-2026-07-17 behavior, with
        // SKIPPED riding through `default: .completed` — correct by luck, not by construction).
        switch UploadExtensionRoot.shared.process() {
        case .completed:
            return .completed
        case .skipped:
            // Nothing to do (no membership / membership contributes nothing) — the system rests.
            return .completed
        case .processing:
            // The in-flight cap was hit, or pending jobs remain — ask the system to run us again.
            return .processing
        case .failed:
            return .failure
        default:
            return .failure
        }
    }

    func notifyTermination() {
        // v1: the Kotlin cycle is synchronous (runBlocking), so there is nothing in flight to
        // interrupt or persist here.
    }
}
