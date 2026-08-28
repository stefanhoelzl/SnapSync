package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The destination-path extractor the ledger records an upload under (capability `sync-ledger`).
 *
 * It exists so a returned `PHAssetResourceUploadJob` can be matched back to the row that created it
 * without a platform URL type. The path and NOT the query, because the query is the capture filename —
 * which the OS is free to hand back re-encoded, and which says nothing about which row this is.
 */
class DestinationPathTest {

    @Test
    fun the_path_is_taken_and_the_query_is_left_behind() {
        assertEquals(
            "/api/v2/files/devices/D/ABC-123_L0_001/primary",
            destinationPathOf(
                "https://edge.example/api/v2/files/devices/D/ABC-123_L0_001/primary?filename=IMG_0042.HEIC",
            ),
        )
    }

    @Test
    fun a_fragment_ends_the_path_too() {
        assertEquals("/a/b", destinationPathOf("https://edge.example/a/b#frag"))
    }

    @Test
    fun a_url_with_no_path_is_the_root() {
        // "" and "/" are the same destination and must not be told apart by accident.
        assertEquals("/", destinationPathOf("https://edge.example"))
        assertEquals("/", destinationPathOf("https://edge.example?x=1"))
        assertEquals("/", destinationPathOf("https://edge.example/"))
    }

    @Test
    fun a_port_in_the_authority_does_not_start_the_path() {
        // The local rig is `http://127.0.0.1:8080/…`; a colon-seeking parser cuts this in the wrong place.
        assertEquals("/api/v2/files", destinationPathOf("http://127.0.0.1:8080/api/v2/files"))
    }

    @Test
    fun percent_encoding_is_preserved_exactly_as_composed() {
        // The row this matches was recorded from the same composition, so decoding here could only make
        // the two spellings disagree.
        assertEquals("/files/a%20b/primary", destinationPathOf("https://e.example/files/a%20b/primary?f=x"))
    }
}
