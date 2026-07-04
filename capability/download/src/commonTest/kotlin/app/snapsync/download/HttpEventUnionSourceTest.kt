package app.snapsync.download

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HttpEventUnionSourceTest {

    private fun client(status: HttpStatusCode, body: String) = HttpClient(
        MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) },
    )

    @Test
    fun parses_union_assets_with_device_and_resources() = runTest {
        val body = """
        [
          {"deviceId":"DEV-A","assetId":"Q","creationDate":"2026-06-30T10:00:00Z","resources":[
            {"role":"primary","contentType":"image/heic","key":"Q-primary.heic","filename":"IMG.HEIC","size":10,"url":"https://e/devices/DEV-A/files/Q-primary.heic"},
            {"role":"live","contentType":"video/quicktime","key":"Q-live.mov","filename":"IMG.MOV","size":20,"url":"https://e/devices/DEV-A/files/Q-live.mov"}
          ]}
        ]
        """.trimIndent()
        val result = HttpEventUnionSource(client(HttpStatusCode.OK, body), "https://e").union("EVENT")
        val assets = result.getOrThrow()
        assertEquals(1, assets.size)
        assertEquals("DEV-A", assets[0].deviceId)
        assertEquals("Q", assets[0].assetId)
        assertEquals(listOf("primary", "live"), assets[0].resources.map { it.role })
        assertEquals("https://e/devices/DEV-A/files/Q-live.mov", assets[0].resources[1].url)
        assertEquals("IMG.MOV", assets[0].resources[1].originalFilename)
    }

    @Test
    fun non_2xx_is_a_failed_result() = runTest {
        val result = HttpEventUnionSource(client(HttpStatusCode.BadGateway, "nope"), "https://e").union("EVENT")
        assertTrue(result.isFailure)
    }

    @Test
    fun empty_union_is_empty_list() = runTest {
        val result = HttpEventUnionSource(client(HttpStatusCode.OK, "[]"), "https://e").union("EVENT")
        assertEquals(emptyList(), result.getOrThrow())
    }
}
