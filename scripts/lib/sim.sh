# Shared by scripts/sim-contracts and scripts/sim-settle (POSIX sh, sourced).

# The runner image's own iPhone 17 on its newest iOS runtime, by UDID; nothing when the image carries none.
image_iphone() {
    xcrun simctl list -j devices available | python3 -c '
import json, sys
runtimes = json.load(sys.stdin)["devices"]
ios = sorted((r for r in runtimes if ".SimRuntime.iOS-" in r),
             key=lambda r: [int(x) for x in r.rsplit("iOS-", 1)[1].split("-")], reverse=True)
for r in ios:
    for d in runtimes[r]:
        if d["name"] == "iPhone 17":
            print(d["udid"]); sys.exit(0)
'
}

# The simulator's own daemons that nothing tested needs, stopped for the rest of the device's life: `disable` keeps
# launchd from starting one again, across reboots, and `bootout` ends a running one. Never fatal: a simulator that keeps
# one is only slower.
# - apsd, the push daemon, loops reconnecting to sandbox.push.apple.com from the runner: ~5 300 TCP+TLS attempts and
#   1.1 M log lines in six minutes, and serving that log cost `diagnosticd` ~170 s of CPU between boot and the app's
#   first photo write (runs 36181223846, 36185184684). The app has no APNs entitlement, and its registration fails
#   before apsd is asked. Stopping it cut that CPU to 67–104 s (run 36188792139, three attempts).
# - mediaanalysisd (+ its .service) and photoanalysisd, the Photos library's on-device analysis, which the scheduler
#   starts minutes after boot, once the contracts have imported photos: ~2.3 of the runner's 3 cores. In every
#   `journeys (ios)` failure of 2026-10-05/06 (runs 37380983503, 37473734850, 37491092872, 37492840735) it was running
#   during the journey's create, which stalled the app AND the local api until the app's 5 s timeout fired (the api
#   answered 201 after 10–17 s); in none of 11 passing runs was it. The app reads and writes the library, never its
#   analysis. Stopped, it cannot be restarted (`kickstart` finds no service) and `addmedia` still lands at once.
# - The rest, measured on a stock simulator against one with them stopped (second boots, 6 min, iPhone 16 / iOS 26.5,
#   2026-10-06): 190 → 95 CPU-s besides the two above, a share of it the log daemon serving their lines. Home-screen
#   widgets (chronod) and wallpapers (PosterBoard, postersyncd), the App Store's engagement daemon (amsengagementd, at
#   36–112% during the journeys of two of the failing runs), Shortcuts, Screen Time, Health, Fitness, Game Center,
#   Weather, Reminders, News, Siri suggestions and Spotlight knowledge. SpringBoard, `addmedia` and Photos ran unharmed.
STOPPED_DAEMONS="apsd mediaanalysisd mediaanalysisd.service photoanalysisd amsengagementd MapKit.SnapshotService
    chronod PosterBoard contacts.postersyncd siriactionsd ShortcutsUI ScreenTimeAgent intelligenceplatformd
    fitnessintelligenced fitnesscoachingd healthd healthappd healtheventsd healthrecordsd healthcontentd finhealthd
    gamed weatherd remindd newsd suggestd spotlightknowledged spotlightknowledged.updater"

# Stop them on booted simulator $1, then list any still loaded.
stop_daemons() {
    for daemon in $STOPPED_DAEMONS; do
        xcrun simctl spawn "$1" launchctl disable "system/com.apple.$daemon"
        xcrun simctl spawn "$1" launchctl bootout "system/com.apple.$daemon"
    done 2>&1 | grep -v 'Please switch to user/foreground'
    echo "still loaded after the stop:"
    xcrun simctl spawn "$1" launchctl list | awk '{ print $3 }' | while read -r label; do
        for daemon in $STOPPED_DAEMONS; do [ "$label" = "com.apple.$daemon" ] && echo "  $label"; done
    done
}
