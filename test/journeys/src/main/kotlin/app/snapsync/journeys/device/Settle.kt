package app.snapsync.journeys.device

import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// What a simulator is settled into — the `journeys (ios)` CI job's simulator cache (`ci.yml`) is keyed on THIS FILE,
// so a change here settles the cached device again, and nothing else does.

/**
 * The simulator's own daemons that nothing tested needs, stopped for the rest of the device's life. Never fatal: a
 * simulator that keeps one is only slower.
 * - apsd, the push daemon, loops reconnecting to sandbox.push.apple.com from the runner: ~5 300 TCP+TLS attempts and
 *   1.1 M log lines in six minutes, and serving that log cost `diagnosticd` ~170 s of CPU between boot and the app's
 *   first photo write (runs 36181223846, 36185184684). The app has no APNs entitlement, and its registration fails
 *   before apsd is asked. Stopping it cut that CPU to 67–104 s (run 36188792139, three attempts).
 * - mediaanalysisd (+ its .service) and photoanalysisd, the Photos library's on-device analysis, which the scheduler
 *   starts minutes after boot, once the contracts have imported photos: ~2.3 of the runner's 3 cores. In every
 *   `journeys (ios)` failure of 2026-10-05/06 (runs 37380983503, 37473734850, 37491092872, 37492840735) it was running
 *   during the journey's create, which stalled the app AND the local api until the app's 5 s timeout fired (the api
 *   answered 201 after 10–17 s); in none of 11 passing runs was it. The app reads and writes the library, never its
 *   analysis. Stopped, it cannot be restarted (`kickstart` finds no service) and `addmedia` still lands at once.
 * - The rest, measured on a stock simulator against one with them stopped (second boots, 6 min, iPhone 16 / iOS 26.5,
 *   2026-10-06): 190 → 95 CPU-s besides the two above, a share of it the log daemon serving their lines. Home-screen
 *   widgets (chronod) and wallpapers (PosterBoard, postersyncd), the App Store's engagement daemon (amsengagementd, at
 *   36–112% during the journeys of two of the failing runs), Shortcuts, Screen Time, Health, Fitness, Game Center,
 *   Weather, Reminders, News, Siri suggestions and Spotlight knowledge. SpringBoard, `addmedia` and Photos ran unharmed.
 */
val STOPPED_DAEMONS: List<String> = listOf(
    "apsd", "mediaanalysisd", "mediaanalysisd.service", "photoanalysisd", "amsengagementd", "MapKit.SnapshotService",
    "chronod", "PosterBoard", "contacts.postersyncd", "siriactionsd", "ShortcutsUI", "ScreenTimeAgent",
    "intelligenceplatformd", "fitnessintelligenced", "fitnesscoachingd", "healthd", "healthappd", "healtheventsd",
    "healthrecordsd", "healthcontentd", "finhealthd", "gamed", "weatherd", "remindd", "newsd", "suggestd",
    "spotlightknowledged", "spotlightknowledged.updater",
)

/** Photos: opening it opens the system library, whose preparation then starts. */
const val PHOTOS = "com.apple.mobileslideshow"

/**
 * Settles simulator [udid] once, so a cache can hand it to later runs already past its first-boot work. A runner's
 * first boot of the image's iPhone 17 spends minutes, at a load of 100-480 on 3 vCPUs, building what the device keeps
 * in its own directory: the LaunchServices database, widget and wallpaper registrations, the Photos library and its
 * caches (run 38393803729: boot 1.8 min, then 2.6 min to install the app under that load). This boots it, stops the
 * daemons nothing tested needs ([STOPPED_DAEMONS] — `disable` persists, so a later boot never starts them), opens Photos
 * and awaits its library's background migration, waits for the load to fall, and shuts it down cleanly. No app is
 * installed and no photo is added: the device stays as fresh as the image shipped it, only past its first boot.
 */
fun settle(simulators: SimulatorDriver, shell: Shell, udid: String, log: File, say: (String) -> Unit) {
    say("booting $udid")
    simulators.boot(udid)
    say("booted; still loaded after the daemon stop: ${simulators.stopDaemons(udid, STOPPED_DAEMONS)}")
    simulators.launch(udid, PHOTOS)
    // The system library's background migration, which `dasd` runs when it chooses — minutes later, 3-449 s measured.
    // Awaited here, so the cached library is already migrated; never fatal, a run awaits it again.
    say("photo library: ${awaitPhotoMigration(shell, udid, log, 15.minutes)}")
    // Settled: the 1-minute load under 4 for three samples in a row, 10 s apart — at most 10 min, after which it is
    // saved as it is (a later boot finishes what is left).
    var calm = 0
    var samples = 0
    var load = Double.NaN
    while (calm < CALM_SAMPLES && samples < MAX_SAMPLES) {
        Thread.sleep(SAMPLE_EVERY.inWholeMilliseconds)
        samples++
        load = shell.run(listOf("sysctl", "-n", "vm.loadavg"), SAMPLE_EVERY).out.trim().removePrefix("{").trim()
            .split(' ').firstOrNull()?.toDoubleOrNull() ?: Double.NaN
        calm = if (load < CALM_LOAD) calm + 1 else 0
        if (samples % SAY_EVERY == 0) say("load $load")
    }
    say("settled after ${samples * SAMPLE_EVERY.inWholeSeconds} s (load $load)")
    simulators.terminate(udid, PHOTOS)
    simulators.shutdown(udid)
    say("shut down")
}

private const val CALM_SAMPLES = 3
private const val MAX_SAMPLES = 60
private const val CALM_LOAD = 4.0
private const val SAY_EVERY = 6
private val SAMPLE_EVERY = 10.seconds
