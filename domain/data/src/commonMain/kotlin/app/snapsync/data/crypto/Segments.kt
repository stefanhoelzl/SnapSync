package app.snapsync.data.crypto

import app.snapsync.format.EncryptedFileFormat
import app.snapsync.format.FileHead
import app.snapsync.format.SegmentRead
import app.snapsync.ports.AesKey
import app.snapsync.ports.Crypto
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileFailure
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files

/**
 * A file's segments, handed to a [SegmentStep] one at a time from an offset on — each of a [SegmentLength] but the
 * last, which is whatever remains. A segment is known to be the last when reading one byte past it finds nothing, so
 * the end of the file alone decides, as the format requires ([EncryptedFileFormat.segmentOf]). The walk ends at the
 * first segment its step ends it with, or at the first read that fails.
 */
internal class Segments(private val files: Files) {
    fun <S : Any> walk(area: FileArea, path: String, start: Long, length: SegmentLength, step: SegmentStep<S>): S {
        var cursor = Cursor(start, 0)
        while (true) {
            at(area, path, cursor, length, step)?.let { return it }
            cursor = cursor.next(length)
        }
    }

    private fun <S : Any> at(
        area: FileArea,
        path: String,
        at: Cursor,
        length: SegmentLength,
        step: SegmentStep<S>,
    ): S? =
        when (val read = files.readRange(area, path, at.offset, length.of(at.segment) + 1)) {
            is FileResult.Ok -> taken(at, EncryptedFileFormat.segmentOf(read.value, length.of(at.segment)), step)
            is FileFailure -> step.unreadable(read)
        }

    private fun <S : Any> taken(at: Cursor, read: SegmentRead, step: SegmentStep<S>): S? = when (read) {
        is SegmentRead.More -> step.more(at.segment, read.bytes)
        is SegmentRead.Last -> step.last(at.segment, read.bytes)
    }
}

/** Where a walk is: [offset] in the file, at segment [segment]. */
internal class Cursor(val offset: Long, val segment: Int) {
    fun next(length: SegmentLength): Cursor = Cursor(offset + length.of(segment), segment + 1)
}

/** How long a segment that is not the last is. */
internal interface SegmentLength {
    fun of(segment: Int): Int
}

/** A plaintext's segments: what a seal reads. */
internal object Plaintext : SegmentLength {
    override fun of(segment: Int): Int = EncryptedFileFormat.plaintextSegmentLength(segment)
}

/** A sealed file's segments, after its head: what an opening reads. */
internal object Ciphertext : SegmentLength {
    override fun of(segment: Int): Int = EncryptedFileFormat.ciphertextSegmentLength(segment)
}

/** One walk's work per segment; [S] is how the walk ends. */
internal interface SegmentStep<S : Any> {
    /** A segment more follow: `null` to go on, else how the walk ends here. */
    fun more(segment: Int, bytes: ByteArray): S?

    /** The last segment: how the walk ends. */
    fun last(segment: Int, bytes: ByteArray): S

    /** A read that failed: how the walk ends. */
    fun unreadable(failure: FileFailure): S
}

/** A seal's step: each plaintext segment sealed and appended to the part. */
internal class Sealing(
    private val crypto: Crypto,
    private val part: Part,
    private val key: AesKey,
    private val head: FileHead,
) : SegmentStep<FileResult<Unit>> {
    override fun more(
        segment: Int,
        bytes: ByteArray,
    ): FileResult<Unit>? = when (val written = sealed(segment, bytes, false)) {
        is FileResult.Ok -> null
        is FileFailure -> written
    }

    override fun last(segment: Int, bytes: ByteArray): FileResult<Unit> = sealed(segment, bytes, true)

    override fun unreadable(failure: FileFailure): FileResult<Unit> = failure

    private fun sealed(segment: Int, bytes: ByteArray, last: Boolean): FileResult<Unit> =
        part.append(crypto.aesGcmSeal(key, nonceOf(head, segment, last), bytes))
}

/** An opening's step: each segment authenticated and its plaintext appended to the part. */
internal class Unsealing(
    private val crypto: Crypto,
    private val part: Part,
    private val key: AesKey,
    private val head: FileHead,
) : SegmentStep<Opened> {
    override fun more(segment: Int, bytes: ByteArray): Opened? = when (val plain = opened(segment, bytes, false)) {
        is Authenticated.Plain -> going(part.append(plain.bytes))
        is Authenticated.Not -> Opened.Damaged(segment)
    }

    override fun last(segment: Int, bytes: ByteArray): Opened = when (val plain = opened(segment, bytes, true)) {
        is Authenticated.Plain -> ended(part.append(plain.bytes))
        is Authenticated.Not -> Opened.Damaged(segment)
    }

    override fun unreadable(failure: FileFailure): Opened = Opened.Unreadable(failure)

    private fun opened(segment: Int, bytes: ByteArray, last: Boolean): Authenticated =
        crypto.aesGcmOpen(key, nonceOf(head, segment, last), bytes)?.let(Authenticated::Plain) ?: Authenticated.Not
}

/** Whether a segment authenticated, and its plaintext when it did. */
internal sealed interface Authenticated {
    class Plain(val bytes: ByteArray) : Authenticated

    data object Not : Authenticated
}

/** How an opening's walk ended. */
internal sealed interface Opened {
    data object Whole : Opened

    class Damaged(val segment: Int) : Opened

    class Unreadable(val failure: FileFailure) : Opened
}

private fun going(written: FileResult<Unit>): Opened? = when (written) {
    is FileResult.Ok -> null
    is FileFailure -> Opened.Unreadable(written)
}

private fun ended(written: FileResult<Unit>): Opened = when (written) {
    is FileResult.Ok -> Opened.Whole
    is FileFailure -> Opened.Unreadable(written)
}

/** The output being built: `<to>.part`, moved to `<to>` only once whole, removed on any failure. */
internal class Part(private val files: Files, val area: FileArea, private val to: String) {
    val path = of(to)

    companion object {
        /** Where the output for [to] is built. */
        fun of(to: String): String = "$to.part"
    }

    /** A part a crash left behind is never built on. */
    fun begun(): Part = also { files.delete(area, path) }

    fun append(bytes: ByteArray): FileResult<Unit> = files.append(area, path, bytes)

    /** A seal's [built] part, moved into place when whole — else removed. */
    fun placed(built: FileResult<Unit>): FileResult<Unit> = when (built) {
        is FileResult.Ok -> moved(files.move(area, path, to))
        is FileFailure -> removed(built)
    }

    /** An opening's [walked] part, moved into place when whole — else removed. */
    fun placed(walked: Opened): OpenOutcome = when (walked) {
        is Opened.Whole -> opened(files.move(area, path, to))
        is Opened.Damaged -> removed(OpenOutcome.Damaged("segment ${walked.segment} failed to authenticate"))
        is Opened.Unreadable -> removed(OpenOutcome.Unreadable(walked.failure))
    }

    private fun moved(moved: FileResult<Unit>): FileResult<Unit> = when (moved) {
        is FileResult.Ok -> moved
        is FileFailure -> removed(moved)
    }

    private fun opened(moved: FileResult<Unit>): OpenOutcome = when (moved) {
        is FileResult.Ok -> OpenOutcome.Opened
        is FileFailure -> removed(OpenOutcome.Unreadable(moved))
    }

    private fun <T> removed(outcome: T): T = outcome.also { files.delete(area, path) }
}
