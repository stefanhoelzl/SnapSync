package app.snapsync.contracts

import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.numberWithLongLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FoundationRenderingTest {

    @Test
    fun `an answer renders sorted and parses back to the types the adapter reads`() {
        val data = parseAttributes("v_Data=d:73656564").getValue("v_Data")
        val rendered = renderAttributes(
            mapOf("pdmn" to "ck", "v_Data" to data, "sync" to NSNumber.numberWithLongLong(0), "acct" to "a b"),
        )
        assertEquals("acct=s:a%20b pdmn=s:ck sync=n:0 v_Data=d:73656564", rendered)

        val parsed = parseAttributes(rendered)
        assertEquals("a b", parsed["acct"])
        assertEquals("ck", parsed["pdmn"])
        assertIs<NSData>(parsed["v_Data"])
        assertEquals(rendered, renderAttributes(parsed), "render . parse is the identity on a rendering")
    }

    @Test
    fun `a masked value parses back as a string`() {
        assertEquals("<masked>", parseAttributes("cdat=<masked>")["cdat"])
    }
}
