package app.snapsync.ios.upload

import app.snapsync.model.UploadRequest
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue

/**
 * Build the edge PUT request for [request] — HTTP/3 disabled (see below). Shared by both upload tiers'
 * transports, which is why it lives beside neither of them.
 *
 * Force HTTP/2-over-TCP: the system otherwise performs the upload over HTTP/3 (QUIC) against the public edge
 * endpoint, but that QUIC connection never completes on real networks (it hangs ~11s, cancels, and retries
 * forever — nothing uploads), with no TCP fallback. The edge only offers h2/http1.1 anyway. Opting the request
 * out of HTTP/3 keeps uploads on TCP.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun uploadUrlRequest(url: NSURL, request: UploadRequest): NSMutableURLRequest {
    val urlRequest = NSMutableURLRequest(uRL = url)
    urlRequest.setHTTPMethod("PUT")
    request.headers.forEach { (name, value) -> urlRequest.setValue(value, forHTTPHeaderField = name) }
    urlRequest.setAssumesHTTP3Capable(false)
    return urlRequest
}
