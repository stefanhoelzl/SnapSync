package app.snapsync.domain.report

/**
 * What a process is called in its own log's first line: its id where it has one — the app's or the upload extension's
 * bundle, the Android package — and its platform where it has none (a test executable), so a reader who concatenates
 * the app's and the extension's logs can always tell runs apart.
 */
object ProcessName {
    fun of(id: String?, platform: String): String = id ?: platform
}
