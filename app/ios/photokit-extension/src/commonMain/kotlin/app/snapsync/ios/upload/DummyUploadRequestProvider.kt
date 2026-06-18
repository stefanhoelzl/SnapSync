package app.snapsync.ios.upload

import app.snapsync.engine.Resource
import app.snapsync.engine.UploadRequest
import app.snapsync.engine.UploadRequestProvider
import co.touchlab.kermit.Logger

/**
 * The bring-up slice's non-uploading [UploadRequestProvider]: mints a deterministic dummy
 * destination for each resource and **logs it** — this is where the slice "emits the dummy URL".
 * It is the swap-in twin of `S3UploadRequestProvider`; the extension composition root injects this
 * now and swaps the real provider in later. No network work happens here, and the system never
 * reaches `dummy.invalid`, so jobs created with these destinations cannot really upload.
 *
 * The contract is still honored: `filename -> destination` is deterministic and injective (distinct
 * filenames percent-encode to distinct URLs), so the engine's idempotency assumptions hold.
 */
class DummyUploadRequestProvider(
    private val log: Logger = Logger.withTag("DummyUpload"),
) : UploadRequestProvider {

    override suspend fun provide(resource: Resource): UploadRequest {
        val url = BASE + encode(resource.filename)
        val headers = mapOf("content-type" to resource.contentType)
        log.i { "emit dummy upload: ${resource.filename} -> $url" }
        return UploadRequest(url = url, headers = headers, resource = resource)
    }

    // Percent-encode every byte outside the RFC 3986 unreserved set, so distinct filenames never
    // collide in the path (the deterministic-and-injective contract).
    private fun encode(filename: String): String = buildString {
        for (byte in filename.encodeToByteArray()) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char in UNRESERVED) {
                append(char)
            } else {
                append('%').append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
        }
    }

    private companion object {
        const val BASE = "https://dummy.invalid/"
        const val HEX = "0123456789ABCDEF"
        val UNRESERVED: Set<Char> =
            (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')).toSet()
    }
}
