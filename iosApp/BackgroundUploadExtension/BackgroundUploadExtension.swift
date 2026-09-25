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

    // How the OS calls this, measured (SE2, iOS 26.6, 2026-09-23) and asserted by no clause: enabling the
    // registration brings a call in ~1 s and a new photo in ~3 s; a job finishing brings none. Jobs created here
    // upload only AFTER this returns — a `PROCESSING` return after creating brings the next call in ~1 s, one
    // that created nothing brings it 5 min later. See changes/archive/2026-09-23-contract-upload-job-tier.
    func process() -> PHBackgroundResourceUploadProcessingResult {
        PHBackgroundResourceUploadProcessingResult(
            rawValue: Int(UploadExtensionRoot.shared.processRawValue())
        ) ?? .failure
    }

    // The OS is killing this cycle. There is nothing to interrupt or persist — the Kotlin cycle is
    // synchronous (runBlocking) — but that justifies doing no WORK here, never recording NOTHING:
    // a shell function that forwards nothing is invisible by construction, because this shell is
    // wiring-only and untested by project rule and os_log redacts an interpolated NSLog wholesale.
    // Left silent, a terminated cycle read as a `→ process` with no `← process` and no reason.
    // Measured (SE2, iOS 26.6, 2026-09-23): this arrives ~55 ms after every NORMAL return of process(), while a
    // call past its ~60 s budget is killed WITHOUT it — so it never announces a kill. After a killed call the OS
    // backs off ~6 then ~11 min. See changes/archive/2026-09-23-contract-upload-job-tier.
    func notifyTermination() {
        UploadExtensionRoot.shared.onTerminate()
    }
}
