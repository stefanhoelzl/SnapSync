package app.snapsync.ios.upload

import app.snapsync.model.TransferNetwork
import app.snapsync.model.UploadTarget
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue

/**
 * Build the edge PUT request for [target] — HTTP/3 disabled (see below), and held off restricted networks when the
 * target asks ([applyTransferNetwork]). Shared by both upload tiers' transports, which is why it lives beside neither
 * of them.
 *
 * Force HTTP/2-over-TCP: the system otherwise performs the upload over HTTP/3 (QUIC) against the public edge
 * endpoint, but that QUIC connection never completes on real networks (it hangs ~11s, cancels, and retries
 * forever — nothing uploads), with no TCP fallback. The edge only offers h2/http1.1 anyway. Opting the request
 * out of HTTP/3 keeps uploads on TCP.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun uploadUrlRequest(url: NSURL, target: UploadTarget): NSMutableURLRequest {
    val urlRequest = NSMutableURLRequest(uRL = url)
    urlRequest.setHTTPMethod("PUT")
    target.headers.forEach { (name, value) -> urlRequest.setValue(value, forHTTPHeaderField = name) }
    urlRequest.setAssumesHTTP3Capable(false)
    urlRequest.applyTransferNetwork(target.network)
    return urlRequest
}

/**
 * The member's mobile-data choice (capability `mobile-data`) as the three per-request flags iOS holds a transfer on:
 * no cellular interface, no EXPENSIVE path (cellular, or Wi-Fi from a personal hotspot), no CONSTRAINED path (Low Data
 * Mode). Per request, never per session: one background session carries transfers under both rules, and a transfer
 * keeps the rule it was created with.
 *
 * Measured 2026-10-03 (`openspec/changes/mobile-data-for-photos/design.md`): a background-session upload or download
 * with these flags waits on a hotspot, in Low Data Mode (SE2, iOS 26.6.2) and on cellular (XS, iOS 18.7.10), and
 * completes within seconds of an unrestricted Wi-Fi; without them it completes at once on each. A PhotoKit upload job
 * is held on a hotspot and in Low Data Mode by iOS itself whatever its destination request says — the flags matter
 * there only on cellular, which no phone with both iOS 26.1 and a SIM has measured.
 */
fun NSMutableURLRequest.applyTransferNetwork(network: TransferNetwork) {
    val any = network == TransferNetwork.ANY
    setAllowsCellularAccess(any)
    setAllowsExpensiveNetworkAccess(any)
    setAllowsConstrainedNetworkAccess(any)
}
