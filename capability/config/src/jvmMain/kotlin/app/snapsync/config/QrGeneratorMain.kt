package app.snapsync.config

import app.snapsync.s3.S3Config
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO

/**
 * The authoritative QR generator (design.md D10): encodes the five S3 fields into the canonical
 * `snapsync://config?v=1&d=…` URL via [encodeConfigUrl] — the same codec the app decodes with, so
 * the wire format cannot drift — and renders a scannable QR PNG.
 *
 * Field values come from env vars (`SNAPSYNC_S3_BUCKET`, `_REGION`, `_ENDPOINT`, `_ACCESS_KEY_ID`,
 * `_SECRET_ACCESS_KEY`) or a gitignored `local.properties` (`s3.bucket`, `s3.region`, `s3.endpoint`,
 * `s3.accessKeyId`, `s3.secretAccessKey`); env wins. The secret is never read from a tracked file.
 * Output PNG path: `SNAPSYNC_QR_OUT` / `qr.out`, default `build/snapsync-config-qr.png`.
 */
fun main() {
    val props = Properties().apply {
        File("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

    fun value(env: String, prop: String): String =
        System.getenv(env) ?: props.getProperty(prop)
        ?: error("missing $env (or $prop in local.properties)")

    val config = S3Config(
        bucket = value("SNAPSYNC_S3_BUCKET", "s3.bucket"),
        region = value("SNAPSYNC_S3_REGION", "s3.region"),
        endpoint = value("SNAPSYNC_S3_ENDPOINT", "s3.endpoint"),
        accessKeyId = value("SNAPSYNC_S3_ACCESS_KEY_ID", "s3.accessKeyId"),
        secretAccessKey = value("SNAPSYNC_S3_SECRET_ACCESS_KEY", "s3.secretAccessKey"),
    )

    val url = encodeConfigUrl(config)
    val out = System.getenv("SNAPSYNC_QR_OUT") ?: props.getProperty("qr.out") ?: "build/snapsync-config-qr.png"

    writeQrPng(url, File(out))

    println("SnapSync config deeplink:")
    println(url)
    println("QR written to: ${File(out).absolutePath}")
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
