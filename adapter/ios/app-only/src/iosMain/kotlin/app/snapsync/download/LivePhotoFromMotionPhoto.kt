package app.snapsync.download

import app.snapsync.model.StagedResource
import app.snapsync.model.importFilename
import app.snapsync.model.locateMotionVideo
import app.snapsync.model.motionPresentationTimestampUs
import app.snapsync.model.runCatchingCancellable
import app.snapsync.objc.checkedObjC
import app.snapsync.objc.checkedObjCValue
import app.snapsync.objc.objcBoundary
import app.snapsync.model.withExtension
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusCompleted
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputMetadataAdaptor
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVFileTypeQuickTimeMovie
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeMetadata
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataKeySpaceQuickTimeMetadata
import platform.AVFoundation.AVMutableMetadataItem
import platform.AVFoundation.AVTimedMetadataGroup
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.key
import platform.AVFoundation.keySpace
import platform.AVFoundation.mediaType
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.tracks
import platform.AVFoundation.transform
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreMedia.CMFormatDescriptionRefVar
import platform.CoreMedia.CMMetadataFormatDescriptionCreateWithMetadataSpecifications
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.kCMMetadataFormatDescriptionMetadataSpecificationKey_DataType
import platform.CoreMedia.kCMMetadataFormatDescriptionMetadataSpecificationKey_Identifier
import platform.CoreMedia.kCMMetadataFormatType_Boxed
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.addEntriesFromDictionary
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.ImageIO.CGImageDestinationAddImageFromSource
import platform.ImageIO.CGImageDestinationCopyImageSource
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageMetadataCreateMutable
import platform.ImageIO.CGImageMetadataCreateMutableCopy
import platform.ImageIO.CGImageMetadataCreateXMPData
import platform.ImageIO.CGImageMetadataSetValueMatchingImageProperty
import platform.ImageIO.CGImageSourceCopyMetadataAtIndex
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceGetType
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImageDestinationLossyCompressionQuality
import platform.ImageIO.kCGImageDestinationMergeMetadata
import platform.ImageIO.kCGImageDestinationMetadata
import platform.ImageIO.kCGImagePropertyMakerAppleDictionary
import platform.darwin.NSObjectProtocol
import platform.posix.memcpy
import platform.posix.usleep
import kotlin.coroutines.resume

/**
 * **A received Android motion photo, taken apart into a Live Photo** (capability `receiving-photos`, "An Android
 * motion photo reaches iPhone as a Live Photo"; decision record `changes/archive/2026-10-01-live-motion-unification` D4).
 *
 * A motion photo is one image file whose Google XMP describes a video appended at its end ([locateMotionVideo]). The
 * XMP is read through ImageIO, which parses it for JPEG and HEIC alike without reading the whole file.
 * Photos pairs a still and a video into a Live Photo only when both carry the SAME content identifier: the still in
 * its Apple maker note (key `17`), the video as its QuickTime `content.identifier`, with a `still-image-time` timed
 * metadata track marking the key frame. So:
 *
 * - **the still** is the file up to the trailer, given the identifier. Losslessly when ImageIO lets its metadata copy
 *   carry it; otherwise re-encoded at maximum quality in its own format, because the lossless copy takes metadata only
 *   as XMP and the SDK declares no XMP namespace for Apple's maker note. Which route ran is logged. Either way this is
 *   the receiving member's gallery copy only: the event keeps the sender's file.
 * - **the video** is the trailer's MP4, its tracks passed through into a QuickTime movie unchanged (no re-encode),
 *   with the identifier and the key-frame marker added.
 *
 * Nothing here touches the staged original, so any failure — an unrecognised file, a refused write, an identifier
 * that did not stick — answers null and the importer imports the original as it always has (D6), and a pairing
 * Photos rejects can still fall back to it (D5). Files are written to a scratch directory in the temporary directory,
 * on the same volume as the staged files, which the importer deletes after the attempt and the OS purges.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class LivePhotoFromMotionPhoto(private val log: Logger) {

    /** The two files of a Live Photo, ready for one creation request, and the names they are saved under. */
    class Pair(val still: String, val stillName: String, val video: String, val videoName: String) {
        fun delete() {
            listOf(still, video).forEach(::remove)
        }
    }

    /**
     * The Live Photo [primary] carries, when it is a motion photo this device can take apart; null imports it as it is.
     */
    suspend fun pair(assetId: String, primary: StagedResource): Pair? = withContext(Dispatchers.Default) {
        // The XMP first, through ImageIO, which reads no more of the file than its metadata: every received photo passes
        // here, and only a motion photo is worth reading whole.
        val xmp = xmpOf(primary.stagedPath)?.takeIf { MOTION_MARK in it || LEGACY_MOTION_MARK in it } ?: return@withContext null
        val file = checkedObjCValue("dataWithContentsOfFile") { NSData.dataWithContentsOfFile(primary.stagedPath, 0u, it) }
            .onFailure { log.w(it) { "$assetId: the staged photo is unreadable — imported as it is" } }
            .getOrNull()?.toByteArray() ?: return@withContext null
        val video = locateMotionVideo(xmp, file) ?: return@withContext null
        val identifier = NSUUID().UUIDString
        val dir = NSTemporaryDirectory() + "motion-photo/"
        checkedObjC("createDirectoryAtPath") {
            NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = it)
        }.onFailure { log.w(it) { "$assetId: no scratch directory — imported as it is" } }.getOrNull() ?: return@withContext null
        val name = importFilename(primary.originalFilename, primary.resourceKey)
        val stillIn = "$dir$identifier-in.${extensionOf(name)}"
        val stillOut = "$dir$identifier.${extensionOf(name)}"
        val mp4 = "$dir$identifier.mp4"
        val mov = "$dir$identifier.mov"
        val pair = Pair(stillOut, name, mov, withExtension(name, ".MOV"))
        val made = runCatchingCancellable {
            write(file.copyOfRange(0, video.first), stillIn)
            write(file.copyOfRange(video.first, video.last + 1), mp4)
            val route = tagStill(stillIn, stillOut, identifier) ?: return@runCatchingCancellable null
            val keyFrameUs = motionPresentationTimestampUs(xmp) ?: 0L
            if (!rewrapVideo(mp4, mov, identifier, keyFrameUs)) return@runCatchingCancellable null
            log.i { "$assetId: a motion photo taken apart into a Live Photo (still $route, video ${video.count()} B passed through)" }
            pair
        }.getOrElse {
            log.w(it) { "$assetId: the motion photo could not be taken apart — imported as its still" }
            null
        }
        listOf(stillIn, mp4).forEach(::remove)
        if (made == null) pair.delete()
        made
    }

    /** The image's XMP packet, as ImageIO serialises it (JPEG and HEIC alike), or null when it has none. */
    private fun xmpOf(path: String): String? {
        val source = imageSource(path) ?: return null
        try {
            val metadata = CGImageSourceCopyMetadataAtIndex(source, 0u, null) ?: return null
            val data = CGImageMetadataCreateXMPData(metadata, null)
            CFRelease(metadata)
            data ?: return null
            val bytes = ByteArray(CFDataGetLength(data).toInt())
            if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), CFDataGetBytePtr(data), bytes.size.toULong()) }
            CFRelease(data)
            return bytes.decodeToString()
        } finally {
            CFRelease(source)
        }
    }

    /**
     * Write [input] to [output] carrying [identifier] as its Apple maker note, and answer the route taken — or null
     * when neither route kept the identifier.
     */
    private fun tagStill(input: String, output: String, identifier: String): String? {
        val source = imageSource(input) ?: return null
        try {
            if (copyLosslessly(source, output, identifier) && makerNoteIdentifier(output) == identifier) return "kept losslessly"
            if (reencode(source, output, identifier) && makerNoteIdentifier(output) == identifier) return "re-encoded at quality 1.0"
            log.w { "the still did not keep its content identifier on either route" }
            return null
        } finally {
            CFRelease(source)
        }
    }

    private fun copyLosslessly(source: CGImageSourceRef, output: String, identifier: String): Boolean {
        val existing = CGImageSourceCopyMetadataAtIndex(source, 0u, null)
        val metadata = (if (existing != null) CGImageMetadataCreateMutableCopy(existing) else CGImageMetadataCreateMutable())
            ?: return false
        existing?.let { CFRelease(it) }
        try {
            val set = bridged(identifier) { value ->
                bridged(MAKER_NOTE_IDENTIFIER_KEY) { name ->
                    CGImageMetadataSetValueMatchingImageProperty(metadata, kCGImagePropertyMakerAppleDictionary, name?.reinterpret(), value)
                }
            }
            if (!set) return false
            val destination = createDestination(source, output) ?: return false
            val options = NSMutableDictionary().apply {
                setObject(metadataObject(metadata) ?: return false, forKey = ns(key(kCGImageDestinationMetadata)))
                setObject(NSNumber(bool = true), forKey = ns(key(kCGImageDestinationMergeMetadata)))
            }
            val copied = bridged(options) { CGImageDestinationCopyImageSource(destination, source, it?.reinterpret(), null) }
            CFRelease(destination)
            return copied
        } finally {
            CFRelease(metadata)
        }
    }

    private fun reencode(source: CGImageSourceRef, output: String, identifier: String): Boolean {
        val copied = CGImageSourceCopyPropertiesAtIndex(source, 0u, null)
        val properties = NSMutableDictionary()
        copied?.let { @Suppress("UNCHECKED_CAST") properties.addEntriesFromDictionary(CFBridgingRelease(it) as Map<Any?, *>) }
        val makerKey = key(kCGImagePropertyMakerAppleDictionary)
        val maker = NSMutableDictionary()
        @Suppress("UNCHECKED_CAST")
        (properties.objectForKey(ns(makerKey)) as? Map<Any?, *>)?.let { maker.addEntriesFromDictionary(it) }
        maker.setObject(ns(identifier), forKey = ns(MAKER_NOTE_IDENTIFIER_KEY))
        properties.setObject(maker, forKey = ns(makerKey))
        properties.setObject(NSNumber(double = 1.0), forKey = ns(key(kCGImageDestinationLossyCompressionQuality)))
        val destination = createDestination(source, output) ?: return false
        bridged(properties) { CGImageDestinationAddImageFromSource(destination, source, 0u, it?.reinterpret()) }
        val finalized = CGImageDestinationFinalize(destination)
        CFRelease(destination)
        return finalized
    }

    /** The content identifier [path]'s maker note carries, as a reader of the written file sees it. */
    private fun makerNoteIdentifier(path: String): String? {
        val source = imageSource(path) ?: return null
        try {
            val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null)?.let { CFBridgingRelease(it) as? Map<*, *> }
            val maker = properties?.get(key(kCGImagePropertyMakerAppleDictionary)) as? Map<*, *>
            return maker?.get(MAKER_NOTE_IDENTIFIER_KEY) as? String
        } finally {
            CFRelease(source)
        }
    }

    /**
     * Pass the MP4 at [input]'s video and audio tracks through into a QuickTime movie at [output], unchanged, adding
     * the content [identifier] and a still-image-time marker at [keyFrameUs]. False when AVFoundation refused any step.
     */
    private suspend fun rewrapVideo(input: String, output: String, identifier: String, keyFrameUs: Long): Boolean {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(input), options = null)
        val reader = checkedObjCValue("AVAssetReader") { AVAssetReader(asset = asset, error = it) }.getOrThrow()
        val writer = checkedObjCValue("AVAssetWriter") {
            AVAssetWriter(uRL = NSURL.fileURLWithPath(output), fileType = AVFileTypeQuickTimeMovie, error = it)
        }.getOrThrow()
        writer.metadata = listOf(quickTimeItem(CONTENT_IDENTIFIER, ns(identifier), DATA_TYPE_UTF8))
        val passes = asset.tracks.filterIsInstance<AVAssetTrack>()
            .filter { it.mediaType == AVMediaTypeVideo || it.mediaType == AVMediaTypeAudio }
            .map { track ->
                val out = AVAssetReaderTrackOutput(track = track, outputSettings = null)
                val input = AVAssetWriterInput(mediaType = track.mediaType, outputSettings = null)
                input.transform = track.preferredTransform
                input.expectsMediaDataInRealTime = false
                if (!reader.canAddOutput(out) || !writer.canAddInput(input)) return false
                reader.addOutput(out)
                writer.addInput(input)
                out to input
            }
        if (passes.none { (_, input) -> input.mediaType == AVMediaTypeVideo }) return false
        val marker = stillImageTimeInput() ?: return false
        if (!writer.canAddInput(marker)) return false
        writer.addInput(marker)
        val adaptor = AVAssetWriterInputMetadataAdaptor(assetWriterInput = marker)
        if (!writer.startWriting() || !reader.startReading()) return false
        writer.startSessionAtSourceTime(CMTimeMake(0, 1))
        val keyFrame = AVTimedMetadataGroup(
            items = listOf(quickTimeItem(STILL_IMAGE_TIME, NSNumber(int = 0), DATA_TYPE_INT8)),
            timeRange = CMTimeRangeMake(CMTimeMake(keyFrameUs, MICROS), CMTimeMake(1, KEY_FRAME_TIMESCALE)),
        )
        if (!adaptor.appendTimedMetadataGroup(keyFrame)) return false
        marker.markAsFinished()
        for ((out, input) in passes) {
            while (true) {
                if (!input.readyForMoreMediaData) {
                    usleep(WAIT_MICROS)
                    continue
                }
                val sample = out.copyNextSampleBuffer() ?: break
                val appended = input.appendSampleBuffer(sample)
                CFRelease(sample)
                if (!appended) return false
            }
            input.markAsFinished()
        }
        suspendCancellableCoroutine { cont ->
            writer.finishWritingWithCompletionHandler { objcBoundary(log, "finishWriting") { cont.resume(Unit) } }
        }
        return reader.status == AVAssetReaderStatusCompleted && writer.status == AVAssetWriterStatusCompleted
    }

    /** The writer input for the timed `still-image-time` marker Photos reads a Live Photo's key frame from. */
    private fun stillImageTimeInput(): AVAssetWriterInput? = memScoped {
        val spec = NSMutableDictionary().apply {
            setObject(ns("mdta/$STILL_IMAGE_TIME"), forKey = ns(key(kCMMetadataFormatDescriptionMetadataSpecificationKey_Identifier)))
            setObject(ns(DATA_TYPE_INT8), forKey = ns(key(kCMMetadataFormatDescriptionMetadataSpecificationKey_DataType)))
        }
        val description = alloc<CMFormatDescriptionRefVar>()
        val status = bridged(listOf(spec)) { specs ->
            CMMetadataFormatDescriptionCreateWithMetadataSpecifications(null, kCMMetadataFormatType_Boxed, specs?.reinterpret(), description.ptr)
        }
        val format = description.value
        if (status != 0 || format == null) return@memScoped null
        AVAssetWriterInput(mediaType = AVMediaTypeMetadata, outputSettings = null, sourceFormatHint = format).also { CFRelease(format) }
    }

    private fun quickTimeItem(key: String, value: NSObjectProtocol, dataType: String) = AVMutableMetadataItem().apply {
        this.keySpace = AVMetadataKeySpaceQuickTimeMetadata
        this.key = ns(key)
        setValue(value)
        setDataType(dataType)
    }

    private fun imageSource(path: String): CGImageSourceRef? =
        bridged(NSURL.fileURLWithPath(path)) { CGImageSourceCreateWithURL(it?.reinterpret(), null) }

    private fun createDestination(source: CGImageSourceRef, path: String) =
        bridged(NSURL.fileURLWithPath(path)) { CGImageDestinationCreateWithURL(it?.reinterpret(), CGImageSourceGetType(source), 1u, null) }

    private fun write(bytes: ByteArray, path: String) {
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        checkedObjC("writeToFile") { data.writeToFile(path, 0u, it) }.getOrThrow()
    }

    private companion object {
        /** The maker-note key Photos pairs a Live Photo's still by. */
        const val MAKER_NOTE_IDENTIFIER_KEY = "17"
        const val MOTION_MARK = "MotionPhoto"
        const val LEGACY_MOTION_MARK = "MicroVideo"
        const val CONTENT_IDENTIFIER = "com.apple.quicktime.content.identifier"
        const val STILL_IMAGE_TIME = "com.apple.quicktime.still-image-time"
        const val DATA_TYPE_UTF8 = "com.apple.metadata.datatype.UTF-8"
        const val DATA_TYPE_INT8 = "com.apple.metadata.datatype.int8"
        const val MICROS = 1_000_000
        const val KEY_FRAME_TIMESCALE = 100
        const val WAIT_MICROS = 1_000u

        fun extensionOf(name: String): String = name.substringAfterLast('.', "jpg")

        /** [value] as a CF reference for the length of [block], released after it (toll-free bridging). */
        inline fun <R> bridged(value: Any, block: (CFTypeRef?) -> R): R {
            val ref = CFBridgingRetain(value)
            try {
                return block(ref)
            } finally {
                ref?.let { CFRelease(it) }
            }
        }

        fun ns(value: String): NSString = NSString.create(string = value)

        /** A CF string constant as the NSString key a Foundation dictionary holds. */
        fun key(constant: CFStringRef?): String = CFBridgingRelease(constant?.let { CFRetain(it) }) as String

        /** A CF object as a Foundation object ARC owns, for a Foundation dictionary's value. */
        fun metadataObject(metadata: CFTypeRef): Any? = CFBridgingRelease(CFRetain(metadata))
    }
}

/** Remove a scratch file; one already gone is what a moved or never-written file leaves. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun remove(path: String) {
    checkedObjC("removeItemAtPath") { NSFileManager.defaultManager.removeItemAtPath(path, error = it) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { bytes ->
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
}
