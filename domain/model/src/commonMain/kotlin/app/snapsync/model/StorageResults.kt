package app.snapsync.model

/** One preference read: a value, a definite absence, or "I could not look". */
sealed interface PrefRead {
    data class Value(val value: String) : PrefRead
    data object Absent : PrefRead
    data class Unavailable(val detail: String) : PrefRead
}
