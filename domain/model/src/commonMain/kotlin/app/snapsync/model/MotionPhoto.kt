package app.snapsync.model

/**
 * **Google's motion-photo format**, as bytes (capability `receiving-photos`, "A moving photo arrives moving"): one
 * image file whose XMP describes a video appended at its end. The receiving device converts at import, both ways:
 * Android builds one from a Live Photo's still and video ([motionPhotoStill], then the video appended as delivered),
 * and iPhone takes one apart into a Live Photo ([locateMotionVideo]). Both platforms' import adapters call these; the
 * pure half lives here so it is tested on the JVM.
 *
 * **The packet is the one measured to play** (Galaxy A40, Google Photos 7.84, 2026-09-30; decision record
 * `changes/live-motion-unification` D2): ONE `rdf:Description` carrying both tag sets — the current `Camera:MotionPhoto`
 * with its `Container:Directory`, and the legacy `GCamera:MicroVideo*` — where either alone already played. The
 * `MotionPhoto` item's mime is `video/mp4` even when the appended bytes are an iPhone QuickTime movie: that exact
 * pairing played unchanged, and no other was measured. `Camera` and `GCamera` are two prefixes for ONE namespace, so
 * the two tag sets live in one namespace, as Google's own files have them.
 */
object MotionPhoto {

    /** The Google camera namespace both tag sets use. */
    const val CAMERA_NS: String = "http://ns.google.com/photos/1.0/camera/"

    /** The Google container namespace, whose directory lists the file's items. */
    const val CONTAINER_NS: String = "http://ns.google.com/photos/1.0/container/"

    /** "No particular frame": the player picks. What the spike wrote, and what played. */
    const val UNKNOWN_TIMESTAMP: Long = -1

    /** The mime the directory gives the appended video; see the class doc for why it is mp4 for a MOV too. */
    const val VIDEO_MIME: String = "video/mp4"

    /** The motion properties as one `rdf:Description`, for a packet of its own or merged into an existing one. */
    fun description(videoLength: Long, presentationTimestampUs: Long = UNKNOWN_TIMESTAMP): String =
        """<rdf:Description rdf:about="" xmlns:GCamera="$CAMERA_NS" xmlns:Camera="$CAMERA_NS" """ +
            """xmlns:Container="$CONTAINER_NS" xmlns:Item="${CONTAINER_NS}item/" """ +
            """GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="$videoLength" """ +
            """GCamera:MicroVideoPresentationTimestampUs="$presentationTimestampUs" """ +
            """Camera:MotionPhoto="1" Camera:MotionPhotoVersion="1" """ +
            """Camera:MotionPhotoPresentationTimestampUs="$presentationTimestampUs">""" +
            "<Container:Directory><rdf:Seq>" +
            """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" """ +
            """Item:Length="0" Item:Padding="0"/></rdf:li>""" +
            """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="$VIDEO_MIME" Item:Semantic="MotionPhoto" """ +
            """Item:Length="$videoLength" Item:Padding="0"/></rdf:li>""" +
            "</rdf:Seq></Container:Directory></rdf:Description>"

    /** A whole XMP packet holding only the motion properties. */
    fun packet(videoLength: Long, presentationTimestampUs: Long = UNKNOWN_TIMESTAMP): String =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="$RDF_NS">""" +
            description(videoLength, presentationTimestampUs) +
            "</rdf:RDF></x:xmpmeta>"

    internal const val RDF_NS: String = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
}

/**
 * [jpeg] with the motion-photo XMP for a video of [videoLength] bytes — the file that, with the video appended, is a
 * motion photo — or null when the JPEG cannot carry it: not a JPEG, an XMP packet already describing a motion photo,
 * an XMP packet with no `rdf:RDF` to merge into, or a merged packet too large for its one segment. Null means
 * "import the still as it is" (decision record `changes/live-motion-unification` D6), never a failed import.
 *
 * The image data is never touched: only the segments before it change. A JPEG with no XMP gets a new XMP segment
 * after its JFIF/EXIF segments; one with an XMP packet (an iPhone JPEG carries one) gets the motion
 * `rdf:Description` added to it, a second description being as valid RDF as one.
 */
fun motionPhotoStill(jpeg: ByteArray, videoLength: Long): ByteArray? {
    val segments = JpegSegments.leading(jpeg) ?: return null
    val existing = segments.firstOrNull { it.isXmp(jpeg) }
    if (existing == null) {
        val insertAt = segments.takeWhile { it.marker == APP0 || it.isExif(jpeg) }.lastOrNull()?.end ?: SOI_LENGTH
        val segment = JpegSegments.xmpSegment(MotionPhoto.packet(videoLength)) ?: return null
        return jpeg.copyOfRange(0, insertAt) + segment + jpeg.copyOfRange(insertAt, jpeg.size)
    }
    val packet = jpeg.copyOfRange(existing.payloadStart + XMP_HEADER.size, existing.end).decodeToString()
    if ("MicroVideo" in packet || "MotionPhoto" in packet) return null
    val rdf = Regex("<rdf:RDF\\b[^>]*>").find(packet) ?: return null
    val merged = packet.substring(0, rdf.range.last + 1) + MotionPhoto.description(videoLength) +
        packet.substring(rdf.range.last + 1)
    val segment = JpegSegments.xmpSegment(merged) ?: return null
    return jpeg.copyOfRange(0, existing.start) + segment + jpeg.copyOfRange(existing.end, jpeg.size)
}

/**
 * The byte range of the video a motion photo [file] carries at its end, as its XMP [xmp] describes it — the
 * `MotionPhoto` item's `Item:Length`, else the legacy `MicroVideoOffset` — or null when [xmp] describes none, or the
 * range is implausible: empty, reaching into the file's first bytes, or not starting with an ISO-BMFF `ftyp` box.
 * Null means "not a motion photo this device can take apart"; the file is then imported as it is.
 */
fun locateMotionVideo(xmp: String, file: ByteArray): IntRange? {
    val length = motionItemLength(xmp) ?: legacyOffset(xmp) ?: return null
    if (length <= FTYP_PROBE || length > file.size - MIN_STILL_BYTES) return null
    val start = file.size - length.toInt()
    val box = file.copyOfRange(start + 4, start + FTYP_PROBE).decodeToString()
    return if (box == "ftyp") start until file.size else null
}

/** The still's presentation time the XMP declares, in microseconds, or null when it declares none (or −1). */
fun motionPresentationTimestampUs(xmp: String): Long? =
    (property(xmp, "MotionPhotoPresentationTimestampUs") ?: property(xmp, "MicroVideoPresentationTimestampUs"))
        ?.takeIf { it >= 0 }

/** The XMP packet of a JPEG, or null when it has none (or is not a JPEG). */
fun jpegXmp(jpeg: ByteArray): String? {
    val segments = JpegSegments.leading(jpeg) ?: return null
    val xmp = segments.firstOrNull { it.isXmp(jpeg) } ?: return null
    return jpeg.copyOfRange(xmp.payloadStart + XMP_HEADER.size, xmp.end).decodeToString()
}

/** [filename] with its extension replaced by [extension] (given with its dot), or appended when it has none. */
fun withExtension(filename: String, extension: String): String {
    val dot = filename.lastIndexOf('.')
    return (if (dot > 0) filename.substring(0, dot) else filename) + extension
}

private fun motionItemLength(xmp: String): Long? =
    Regex("<Container:Item\\b[^>]*>").findAll(xmp)
        .map { it.value }
        .firstOrNull { attribute(it, "Item:Semantic") == "MotionPhoto" }
        ?.let { attribute(it, "Item:Length")?.toLongOrNull() }

private fun legacyOffset(xmp: String): Long? = property(xmp, "MicroVideoOffset")

/** A camera-namespace property in attribute form (`GCamera:Name="1"`) or element form (`<Camera:Name>1</…>`). */
private fun property(xmp: String, name: String): Long? =
    Regex("\\w+:$name\\s*=\\s*\"(-?\\d+)\"").find(xmp)?.groupValues?.get(1)?.toLongOrNull()
        ?: Regex("<\\w+:$name>\\s*(-?\\d+)\\s*</").find(xmp)?.groupValues?.get(1)?.toLongOrNull()

private fun attribute(element: String, name: String): String? =
    Regex("\\b$name\\s*=\\s*\"([^\"]*)\"").find(element)?.groupValues?.get(1)

/** The JPEG segments this codec reads and writes: the ones before the image data. */
internal object JpegSegments {

    class Segment(val marker: Int, val start: Int, val end: Int) {
        /** Where the segment's payload begins: after its marker and its two length bytes. */
        val payloadStart: Int get() = start + 4

        fun isXmp(file: ByteArray): Boolean = marker == APP1 && startsWith(file, payloadStart, XMP_HEADER)
        fun isExif(file: ByteArray): Boolean = marker == APP1 && startsWith(file, payloadStart, EXIF_HEADER)
    }

    /** Every segment from the SOI up to (not including) the start of scan, or null when [jpeg] is not a JPEG. */
    fun leading(jpeg: ByteArray): List<Segment>? {
        if (jpeg.size < SOI_LENGTH || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        val segments = mutableListOf<Segment>()
        var at = SOI_LENGTH
        while (at + 4 <= jpeg.size) {
            if (jpeg[at] != 0xFF.toByte()) return null
            val marker = jpeg[at + 1].toInt() and 0xFF
            if (marker == SOS) return segments
            val length = ((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF)
            val end = at + 2 + length
            if (length < 2 || end > jpeg.size) return null
            segments += Segment(marker, at, end)
            at = end
        }
        return null
    }

    /** An APP1 XMP segment holding [packet], or null when it does not fit one segment. */
    fun xmpSegment(packet: String): ByteArray? {
        val payload = XMP_HEADER + packet.encodeToByteArray()
        val length = payload.size + 2
        if (length > MAX_SEGMENT_LENGTH) return null
        return byteArrayOf(0xFF.toByte(), APP1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun startsWith(file: ByteArray, at: Int, prefix: ByteArray): Boolean =
        at + prefix.size <= file.size && prefix.indices.all { file[at + it] == prefix[it] }
}

private const val SOI_LENGTH = 2
private const val APP0 = 0xE0
private const val APP1 = 0xE1
private const val SOS = 0xDA
private const val MAX_SEGMENT_LENGTH = 0xFFFF

/** An ISO-BMFF file opens with a box whose type, bytes 4–8, is `ftyp`. */
private const val FTYP_PROBE = 8

/** The smallest image part a motion photo can have: an SOI and an EOI. */
private const val MIN_STILL_BYTES = 4

private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray()
private val EXIF_HEADER = "Exif\u0000\u0000".encodeToByteArray()
