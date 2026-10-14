#!/usr/bin/env bash
# Wait out a cold-booted emulator's first-boot rush before anything runs on it, and answer the ANR dialogs it leaves.
#
# A cold boot (`-no-snapshot`) on the runner overloads the guest for its first two minutes or so: in 16 of 20 boots
# (flake-repro run 38570386349) some process missed an ANR deadline 20-101 s after `boot_progress_enable_screen`. For
# SystemUI or the launcher, Android then shows an "Application Not Responding" dialog that stays up until someone
# answers it. The first HOME key a test sends closes system dialogs, and closing that one means "Close app": the
# process is killed then, minutes later, in the middle of a test. A test that runs right after the kill fails. That
# is the AppSettings contract's `NotWithin(20000ms)` in run 38536433078, where SystemUI died just before `openSettings`.
#
# So every dialog is answered here, while nothing is running yet. The step returns once the device has passed the
# rush (MIN_UPTIME), has seen no new ANR and no dialog for QUIET seconds, SystemUI's pid has not changed, and the
# launcher is in front. Past DEADLINE it warns and returns: an unsettled device is the old behaviour, not a failure.
#
# Usage: settle.sh                                    (adb from PATH, or $ADB; one device, or $ANDROID_SERIAL)
set -uo pipefail

ADB="${ADB:-adb}"
MIN_UPTIME="${SETTLE_MIN_UPTIME:-150}"   # guest seconds since kernel start; the screen comes on at ~40 s
QUIET="${SETTLE_QUIET:-30}"              # an ANR's dialog appears ~10 s after its am_anr
DEADLINE="${SETTLE_DEADLINE:-300}"

on_device() { "$ADB" shell "$@" | tr -d '\r'; }
last_anr() { on_device logcat -b events -d -s am_anr | grep am_anr | tail -1; }
say() { echo "settle +$((SECONDS - start))s: $*"; }

start=$SECONDS
timeout 300 "$ADB" shell am wait-for-broadcast-idle >/dev/null || say "broadcasts never went idle"
home=$(on_device cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)
home_package=${home%%/*}
anr=$(last_anr)
systemui=$(on_device pidof com.android.systemui)
quiet_since=$SECONDS
say "launcher ${home_package}, SystemUI pid ${systemui:-none}${anr:+, last ANR: $anr}"

while :; do
  changes=()

  dialogs=$(on_device dumpsys window windows | grep -oE 'Application (Not Responding|Error): [A-Za-z0-9._]+' | sort -u)
  if [ -n "$dialogs" ]; then
    changes+=("answered ${dialogs//$'\n'/, } with HOME")
    on_device input keyevent KEYCODE_HOME
  fi

  now_anr=$(last_anr)
  if [ "$now_anr" != "$anr" ]; then
    changes+=("new ANR: ${now_anr#*am_anr*: }")
    anr=$now_anr
  fi

  now_systemui=$(on_device pidof com.android.systemui)
  if [ -z "$now_systemui" ] || [ "$now_systemui" != "$systemui" ]; then
    changes+=("SystemUI pid ${systemui:-none} -> ${now_systemui:-none}")
    systemui=$now_systemui
  fi

  resumed=$(on_device dumpsys activity activities | grep -m1 'ResumedActivity')
  if [ -z "$dialogs" ] && [[ "$resumed" != *"$home_package"* ]]; then
    changes+=("launcher not in front (${resumed##*ResumedActivity: }), HOME")
    on_device input keyevent KEYCODE_HOME
  fi

  if [ ${#changes[@]} -gt 0 ]; then
    say "$(IFS=';'; echo "${changes[*]}")"
    quiet_since=$SECONDS
  fi

  uptime=$(on_device cut -d. -f1 /proc/uptime)
  if [ ${#changes[@]} -eq 0 ] && [ "${uptime:-0}" -ge "$MIN_UPTIME" ] && [ $((SECONDS - quiet_since)) -ge "$QUIET" ]; then
    say "settled (uptime ${uptime} s, quiet ${QUIET} s)"
    exit 0
  fi
  if [ $((SECONDS - start)) -ge "$DEADLINE" ]; then
    echo "::warning::the emulator did not settle within ${DEADLINE} s; running on it anyway"
    exit 0
  fi
  sleep 2
done
