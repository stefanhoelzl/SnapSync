package app.snapsync.ios.registry

import app.snapsync.ports.UploadExtensionRegistry
import co.touchlab.kermit.Logger

/**
 * **Which implementation of the upload-extension registration this target binds** (capability
 * `ios-photokit-upload`, "The upload-job subsystem binding is fixed by the compilation target").
 *
 * The registration record is OS state exactly as the upload-job queue is, and it is bound the same way and
 * for the same measured reason. `iosArm64` — every shipped binary — binds the PhotoKit implementation.
 * `iosSimulatorArm64` binds a substitute, because on that host the registration is **refused**:
 * `setUploadJobExtensionEnabled(true)` returns `false` with `PHPhotosErrorDomain:-1` under a full grant on
 * a clean device with the extension embedded and signed (measured 2026-08-26, iOS 26.5).
 *
 * Substituting it rather than letting it fail is not a way of hiding the refusal. The refusal is recorded
 * where a reader meets it — `PROBE-FINDINGS.md`, and the `ios-simulator` skill — and a
 * `PHPhotosErrorDomain:-1` reaching a **device** build stays exactly as loud as it is today, at `Error`,
 * because the closed and measured expected-code enumeration is untouched. What substitution buys is that
 * the tier's own contract becomes exercisable: the disable→enable ritual exists to repair a stale `3202`
 * record, and `stop()`'s repair exists to recover the jobs a disable wipes, and neither could be driven
 * anywhere before this.
 *
 * ⏰ **Expiry:** re-measure at the next iOS major, alongside the other PhotoKit platform facts.
 */
expect fun uploadExtensionRegistry(log: Logger): UploadExtensionRegistry
