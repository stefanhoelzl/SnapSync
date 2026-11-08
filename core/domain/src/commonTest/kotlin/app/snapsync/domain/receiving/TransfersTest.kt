package app.snapsync.domain.receiving

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TransfersTest {

    @Test
    fun a_resource_stages_under_its_events_folder_or_where_an_eventless_transfer_always_did() {
        val ref = ref("A")
        assertEquals(
            "download-staging/E_1/${ref.sourceDeviceId.value}/A-primary_x.heic",
            StagedPath.of(StagingFolder.of("E/1"), ref, "A-primary/x.heic").value,
            "a `/` in an event or a key is no segment",
        )
        assertEquals(
            "download-staging/${ref.sourceDeviceId.value}/A-primary.heic",
            StagedPath.of(StagingFolder.of(""), ref, "A-primary.heic").value,
        )
        assertEquals("E", assertIs<StagingFolder.Event>(StagingFolder.of("E")).name)
        assertEquals(StagingFolder.Legacy, StagingFolder.of(""))
    }

    @Test
    fun a_body_is_rejected_only_on_positive_evidence() {
        assertEquals(TransferVerdict.Stage, TransferVerdict.of(TransferFacts(200, 10, 10)))
        assertEquals(TransferVerdict.Stage, TransferVerdict.of(TransferFacts(null, -1, 3)), "unknown status and length")
        assertEquals(TransferVerdict.Stage, TransferVerdict.of(TransferFacts(299, 10, 12)), "an over-length body")
        assertEquals(TransferVerdict.Reject, TransferVerdict.of(TransferFacts(502, -1, 3)), "an error body")
        assertEquals(TransferVerdict.Reject, TransferVerdict.of(TransferFacts(199, -1, 3)))
        assertEquals(TransferVerdict.Reject, TransferVerdict.of(TransferFacts(200, 10, 9)), "short of what it promised")
    }

    @Test
    fun only_an_http_address_with_a_host_is_fetchable() {
        assertEquals(FetchVerdict.Fetchable, FetchVerdict.of("https://edge.example/x?y#z"))
        assertEquals(FetchVerdict.Fetchable, FetchVerdict.of("HTTP://user@edge.example:8080/x"))
        assertEquals(FetchVerdict.Unfetchable, FetchVerdict.of("ftp://edge.example/x"))
        assertEquals(FetchVerdict.Unfetchable, FetchVerdict.of("edge.example/x"))
        assertEquals(FetchVerdict.Unfetchable, FetchVerdict.of("https:///x"))
        assertEquals(FetchVerdict.Unfetchable, FetchVerdict.of("https://user@:8080/x"))
    }

    @Test
    fun the_window_stays_well_under_the_platforms_limit() {
        assertEquals(24, TransferSlots.MAX)
    }
}
