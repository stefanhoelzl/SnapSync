package app.snapsync.journeys.device

/**
 * The jobs of one app that JobScheduler is running or about to run, read from `dumpsys jobscheduler <app>`: the
 * `Slot #…` lines of `Active jobs:` that name the app, and the entries of `Pending queue:` (which reads `None` when
 * empty). A job bound at a force-stop is restarted by the system, process and all — so a force-stop is only clean once
 * this reads zero.
 */
object JobsInFlight {
    fun count(dump: String, app: String): Int {
        val lines = dump.lines()
        val active = section(lines, "Active jobs:").count { it.trimStart().startsWith("Slot #") && "$app/" in it }
        val pending = section(lines, "Pending queue:").count { "$app/" in it }
        return active + pending
    }

    /** The lines under [heading], up to the next unindented line. */
    private fun section(lines: List<String>, heading: String): List<String> {
        val start = lines.indexOfFirst { it.trimEnd() == heading }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { it.isBlank() || it.startsWith(" ") }
    }
}
