package app.snapsync.config

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO

/**
 * The authoritative QR generator (spec: deeplink-config): encodes the runtime config — just the **event id**
 * — into the canonical `snapsync://config?v=3&d=…` URL via [encodeConfigUrl] — the same codec the app
 * decodes with, so the wire format cannot drift — and renders a scannable QR to the terminal (and a
 * PNG fallback). No storage credential is encoded (the device holds none); the upload **host** is not
 * encoded either — it is baked into the IPA at compile time (`BackgroundUploadURLBase`).
 *
 * The event id comes from env var `SNAPSYNC_EVENT_ID` or a gitignored `local.properties`
 * (`snapsync.eventId`); env wins. Output PNG path: `SNAPSYNC_QR_OUT` / `qr.out`, default
 * `build/snapsync-config-qr.png`.
 */
fun main() {
    val props = Properties().apply {
        File("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

    fun value(env: String, prop: String): String =
        System.getenv(env) ?: props.getProperty(prop)
        ?: error("missing $env (or $prop in local.properties)")

    val payload = EventLinkPayload(
        eventId = value("SNAPSYNC_EVENT_ID", "snapsync.eventId"),
    )

    val url = encodeConfigUrl(payload)
    val out = System.getenv("SNAPSYNC_QR_OUT") ?: props.getProperty("qr.out") ?: "build/snapsync-config-qr.png"

    writeQrPng(url, File(out))

    println("SnapSync config deeplink:")
    println(url)
    println()
    println(renderQrToTerminal(url))
    println("QR also written to: ${File(out).absolutePath}")
}

private val ESC = 27.toChar().toString()

/**
 * Render the QR to the terminal with Unicode upper-half blocks (`▀`), two QR modules per text row.
 * Each cell sets the foreground to the top module's colour and the background to the bottom module's
 * — black for dark modules, white for light — so the result is a real black-on-white code (not
 * theme-dependent) at ~1 column per module. A 2-module quiet zone frames it for reliable scanning.
 */
private fun renderQrToTerminal(content: String): String {
    val code = Encoder.encode(content, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
    val m = code.matrix
    val quiet = 2
    val w = m.width + quiet * 2
    val h = m.height + quiet * 2
    fun dark(x: Int, y: Int): Boolean {
        val mx = x - quiet
        val my = y - quiet
        if (mx < 0 || my < 0 || mx >= m.width || my >= m.height) return false
        return m.get(mx, my).toInt() == 1
    }
    val sb = StringBuilder()
    var y = 0
    while (y < h) {
        for (x in 0 until w) {
            val fg = if (dark(x, y)) "30" else "37"          // top module: black=dark, white=light
            val bg = if (dark(x, y + 1)) "40" else "47"      // bottom module
            sb.append(ESC).append('[').append(fg).append(';').append(bg).append('m').append('▀')
        }
        sb.append(ESC).append("[0m\n")
        y += 2
    }
    return sb.toString()
}

private fun writeQrPng(content: String, out: File, size: Int = 512) {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            image.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
        }
    }
    out.absoluteFile.parentFile?.mkdirs()
    ImageIO.write(image, "PNG", out)
}
