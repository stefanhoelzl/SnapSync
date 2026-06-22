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
        // Map the Kotlin tri-state cycle result to the system result. The Kotlin enum entries
        // COMPLETED/PROCESSING/FAILED export to Swift as .completed/.processing/.failed.
        switch UploadExtensionRoot.shared.process() {
        case .processing:
            // The in-flight cap was hit, or pending jobs remain — ask the system to run us again.
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
