package app.snapsync.ios.upload

import app.snapsync.model.TransferNetwork
import app.snapsync.model.UploadTarget
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The member's mobile-data choice as the request flags iOS holds a transfer on (capability `mobile-data`): what iOS
 * DOES with them is measured (`applyTransferNetwork`'s documentation); what is pinned here is that the one request
 * builder both upload tiers share sets all three, and only for a transfer held to unrestricted networks.
 */
class UploadUrlRequestTest {

    private val url = NSURL(string = "https://edge.example/api/v2/files/devices/D/A/primary?filename=IMG.JPG")

    private fun flags(network: TransferNetwork): List<Boolean> =
        uploadUrlRequest(url, UploadTarget(url.absoluteString!!, emptyMap(), network)).let {
            listOf(it.allowsCellularAccess, it.allowsExpensiveNetworkAccess, it.allowsConstrainedNetworkAccess)
        }

    @Test
    fun `a transfer held to unrestricted networks refuses cellular and expensive and constrained paths`() {
        assertEquals(listOf(false, false, false), flags(TransferNetwork.UNRESTRICTED_ONLY))
    }

    @Test
    fun `a transfer on any network allows every path`() {
        assertEquals(listOf(true, true, true), flags(TransferNetwork.ANY))
    }
}
