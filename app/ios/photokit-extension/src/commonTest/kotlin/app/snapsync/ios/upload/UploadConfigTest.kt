package app.snapsync.ios.upload

import app.snapsync.config.S3ConfigPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UploadConfigTest {

    private val payload = S3ConfigPayload(
        bucket = "my-bucket", region = "eu-central-1", accessKeyId = "AK", secretAccessKey = "SK",
    )

    @Test
    fun builds_config_from_payload_and_host() {
        val config = buildS3Config(payload, "http://192.168.1.2:9000")
        assertEquals("http://192.168.1.2:9000", config?.endpoint)
        assertEquals("my-bucket", config?.bucket)
        assertEquals("eu-central-1", config?.region)
        assertEquals("AK", config?.accessKeyId)
        assertEquals("SK", config?.secretAccessKey)
    }

    @Test
    fun null_payload_skips() {
        assertNull(buildS3Config(null, "http://192.168.1.2:9000"))
    }

    @Test
    fun missing_or_blank_host_skips() {
        assertNull(buildS3Config(payload, null))
        assertNull(buildS3Config(payload, ""))
    }
}
