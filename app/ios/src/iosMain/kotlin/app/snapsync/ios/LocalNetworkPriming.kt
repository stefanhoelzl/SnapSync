@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.ios

import app.snapsync.config.ConfigSource
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession

/**
 * Surface and satisfy the iOS Local Network permission before the background-upload extension —
 * which cannot present a prompt — ever runs. When a config payload is present, fire one
 * throwaway request at the compile-time upload host (`BackgroundUploadURLBase`, read from the app
 * bundle). Against a public HTTPS endpoint this is a harmless no-op (no Local Network permission
 * applies); against a private/local host it grants the app-wide permission the extension's uploads
 * depend on. Fire-and-forget: the result is ignored and a failure never affects startup.
 */
internal fun primeLocalNetwork(config: ConfigSource, log: Logger) {
    if (config.config.value == null) return
    val host = NSBundle.mainBundle.objectForInfoDictionaryKey("BackgroundUploadURLBase") as? String
    val url = host?.let { NSURL.URLWithString(it) } ?: return
    log.i { "priming Local Network permission against $host" }
    NSURLSession.sharedSession.dataTaskWithURL(url).resume()
}
