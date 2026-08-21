package app.snapsync.rig.gallery

import app.snapsync.logging.appGroupDirectory
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile

/**
 * The file name the app's identity supplier reads as its fallback source, and the ONLY writer of it.
 *
 * The read side lives in production (`device-identity`, "An identity may be supplied to a host whose secure
 * store cannot serve one") and is **read-only there** — nothing in a shipped build creates this file, which
 * is what makes its presence a discriminator rather than a state. This is the write side, and it exists
 * only in a build the control channel is linked into.
 *
 * ## Why a file, and not a value the channel holds
 *
 * Because the thing that needs the identity is an **OS-initiated cold relaunch**. The OS relaunches a
 * terminated app to deliver `handleEventsForBackgroundURLSession` and calls straight into an entry point —
 * nothing has an opportunity to POST anything first, so an in-memory value planted over HTTP cannot survive
 * the very case it would be planted for. The App Group container is also what the app and the upload
 * extension share, so one file serves both processes without a second mechanism.
 *
 * ## Why not the Keychain
 *
 * Two reasons, either sufficient. The host that needs this is one whose Keychain does not work —
 * `errSecMissingEntitlement` (-34018) on a simulator, where `resolveOrMint`'s adopt and mint branches both
 * fail, not only the read. And `KeychainContainmentTest` scans the whole project outside `build/`, so a
 * `SecItem*` call from this module would be a red build; the containment rule is not exempt for test-only
 * code, deliberately.
 */
const val IDENTITY_FALLBACK_FILE: String = "device-identity-fallback"

/**
 * Write [deviceId] as the fallback identity, returning the path written or `null` if there is no App Group
 * container to write into.
 *
 * Deliberately unconditional: it overwrites. The **never-overwrite** property that matters belongs to the
 * *read* side — a secure store that resolves an identity ignores this file entirely — and enforcing it here
 * as well would mean an operator could not correct a value they had just planted wrongly, which is a
 * different and much cheaper mistake than clobbering a real device's written-once identity.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun plantFallbackIdentity(deviceId: String): PlantOutcome {
    val group = appGroupDirectory()
        ?: return PlantOutcome(path = null, reason = "no App Group container is reachable from this process")
    val path = "$group/$IDENTITY_FALLBACK_FILE"
    return memScoped {
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        // The four-arg form, taking the `NSError**` — `writeToFile` also has a two-arg overload that
        // discards it, and using that one here would make a failed plant look exactly like a successful
        // one that the app then could not find. That is the failure this whole command exists to avoid.
        val ok = NSString.create(string = deviceId)
            .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = errorVar.ptr)
        val error = errorVar.value
        if (ok) {
            PlantOutcome(path = path, reason = null)
        } else {
            PlantOutcome(
                path = null,
                reason = error?.let { "write failed: ${it.code} ${it.localizedDescription}" }
                    ?: "write failed, and the platform reported no error",
            )
        }
    }
}

/**
 * Where the identity landed, or why it did not.
 *
 * A reason rather than a bare null, because the two causes need different actions from the operator: no
 * App Group container means the build is wrong, while a write failure means the container is there and
 * something else is the matter. Collapsing them would send the reader to check the wrong thing.
 */
class PlantOutcome(val path: String?, val reason: String?)
