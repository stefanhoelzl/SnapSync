package app.snapsync.model

/**
 * Where a file lives, as far as the core can say it (`docs/architecture.md`, "Paths are area-relative"). The
 * adapter maps each area to a platform directory; a path inside an area is relative, so no absolute platform
 * path is ever stored or compared by the core.
 */
enum class FileArea {
    /** Shared by the app and the upload extension, readable while the device is locked after its first unlock. */
    SHARED,

    /** This process's own files, never read by the other process. */
    PRIVATE,
}

/**
 * One file operation's answer. "Nothing is there" and "I could not look" are different answers, and a reader
 * that acts on absence needs to know which it got (`docs/architecture.md`, "Absence is never silent"):
 *
 * - [NotFound] — the file (or a directory on its path) does not exist. Only a definite absence answers this.
 * - [AreaUnavailable] — the area itself cannot be reached (a build without the App-Group entitlement).
 * - [Denied] — the file exists and may not be read or written now (a locked device, a permission).
 * - [Failed] — anything else.
 *
 * [Denied] and [Failed] carry diagnostics only: log them, never branch on [code].
 */
sealed interface FileResult<out T> {
    data class Ok<out T>(val value: T) : FileResult<T>
    data object NotFound : FileResult<Nothing>
    data object AreaUnavailable : FileResult<Nothing>
    data class Denied(val detail: String, val code: Long? = null) : FileResult<Nothing>
    data class Failed(val detail: String, val code: Long? = null) : FileResult<Nothing>
}

/** The last bytes of a file, and whether the read began after its first byte (so possibly mid-line). */
class FileTail(val bytes: ByteArray, val cut: Boolean)

/** One preference read: a value, a definite absence, or "I could not look". */
sealed interface PrefRead {
    data class Value(val value: String) : PrefRead
    data object Absent : PrefRead
    data class Unavailable(val detail: String) : PrefRead
}

/** The outcome of a write to a store that may refuse it. [Failed.detail] is diagnostics only. */
sealed interface WriteOutcome {
    data object Ok : WriteOutcome

    /** This platform has no such operation (answered, never thrown, so a caller states what it does instead). */
    data object Unsupported : WriteOutcome
    data class Failed(val detail: String) : WriteOutcome
}
