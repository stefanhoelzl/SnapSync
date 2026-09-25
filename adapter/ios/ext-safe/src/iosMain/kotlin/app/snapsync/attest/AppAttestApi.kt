package app.snapsync.attest

import platform.DeviceCheck.DCAppAttestService
import platform.Foundation.NSData
import platform.Foundation.NSError

/**
 * **The operating-system boundary of [IosAttestKey]**: the four `DCAppAttestService` calls it makes, and
 * nothing else (`docs/architecture.md`, "Hosts CI cannot reach are recorded at the operating-system
 * boundary and replayed on every build").
 *
 * It exists so the adapter can be run against a *recording* of what App Attest answered on a device, where
 * alone the ceremony exists. The seam sits below every decision the adapter makes — above all the SHA-256
 * of the challenge, which is what the edge's verifier recomputes — so a replay exercises the CURRENT
 * adapter against the device's answers, and a change in what it hands App Attest reads as a divergence.
 *
 * The shapes are the platform's own (completion handlers, `NSData`, `NSError`), so the conversions the
 * adapter does are replayed too, not skipped.
 *
 * `internal`: the recording and replaying implementations live in this module's rig-gated source set and
 * its tests.
 */
internal interface AppAttestApi {
    fun isSupported(): Boolean

    fun generateKey(completion: (String?, NSError?) -> Unit)

    fun attestKey(keyId: String, clientDataHash: NSData, completion: (NSData?, NSError?) -> Unit)

    fun generateAssertion(keyId: String, clientDataHash: NSData, completion: (NSData?, NSError?) -> Unit)
}

/** The real App Attest. The only implementation a production build contains. */
internal object SystemAppAttestApi : AppAttestApi {
    private val service: DCAppAttestService get() = DCAppAttestService.sharedService

    override fun isSupported(): Boolean = service.isSupported()

    override fun generateKey(completion: (String?, NSError?) -> Unit) =
        service.generateKeyWithCompletionHandler(completion)

    override fun attestKey(keyId: String, clientDataHash: NSData, completion: (NSData?, NSError?) -> Unit) =
        service.attestKey(keyId, clientDataHash, completion)

    override fun generateAssertion(keyId: String, clientDataHash: NSData, completion: (NSData?, NSError?) -> Unit) =
        service.generateAssertion(keyId, clientDataHash, completion)
}
