#!/usr/bin/env python3
"""Returns once a fresh simulator's system photo library has finished its BACKGROUND migration — the event, read from
`assetsd`'s own log, that the photo-library contracts must not start before.

    scripts/await-photo-migration.py --device <udid> --log build/sim-contracts/photo-migration.log

Why (measured, runs 36868431564 and 37004211450): a new simulator's system library is the runtime's template, an old
schema, and `assetsd` upgrades it in two parts. The foreground part runs as the library opens; the app's first write
waits for it. The background part is a system task `dasd` launches when it chooses — before or after that first write —
and while its longest action runs (`PopulateExtendedAttributes`, 96–184 s at utility QoS on a booting runner) every
write to the library waits. A clause that writes then blocks past its `runTest` bound and fails, wherever it falls.

The two events, both logged by `assetsd`, keyed by `com.apple.assetsd.migration.<st_dev>.<st_ino>` of the library:

    Submitting task request for background migration with identifier com.apple.assetsd.migration.16777230.3098355
    Marking task <BGNonRepeatingSystemTask: com.apple.assetsd.migration.16777230.3098355> complete

The Syndication library is CREATED fresh and submits a task too; it is logged, never awaited (nothing measured shows
it holding the system library's writes). Which id is the system library's is read from the one library whose schema
was upgraded: its migration logs `stat: st_dev=… st_ino=…`. Run AFTER the first write has returned: by then the
library has opened, so every task it will submit has been submitted, and none submitted means none pending.

It follows `log stream` (attached first) and reads `log show` (the past) — no polling, and the narrow predicate keeps
the log daemon's cost negligible. It FAILS rather than guesses: when the system library's id cannot be told apart,
when the stream ends, or when the completion has not come within --bound seconds (a broken platform, not a slow one:
the longest measured migration took 184 s). Stdlib only; dev infrastructure, loads nothing on Linux.
"""
import argparse
import os
import re
import selectors
import subprocess
import sys
import time

PREDICATE = (
    'process == "assetsd" AND (eventMessage CONTAINS "com.apple.assetsd.migration." '
    'OR eventMessage CONTAINS "stat: st_dev=")'
)
STAT = re.compile(r"stat: st_dev=(\d+) st_ino=(\d+)")
SUBMIT = re.compile(r"Submitting task request for background migration with identifier (com\.apple\.assetsd\.migration\.[\d.]+\d)")
COMPLETE = re.compile(r"Marking task <BGNonRepeatingSystemTask: (com\.apple\.assetsd\.migration\.[\d.]+\d)> complete")


class Migrations:
    """What `assetsd` has said about its background migrations, fed one log line at a time."""

    def __init__(self):
        self.upgraded = []  # ids of libraries whose schema was upgraded, in order — the system library's, alone
        self.submitted = []
        self.completed = set()

    def feed(self, line: str) -> None:
        if m := STAT.search(line):
            ident = f"com.apple.assetsd.migration.{m.group(1)}.{m.group(2)}"
            if ident not in self.upgraded:
                self.upgraded.append(ident)
        elif m := SUBMIT.search(line):
            if m.group(1) not in self.submitted:
                self.submitted.append(m.group(1))
        elif m := COMPLETE.search(line):
            self.completed.add(m.group(1))

    def verdict(self) -> tuple[str, str]:
        """("settled" | "waiting" | "unknown", why)."""
        if not self.submitted:
            return "settled", "no background migration was submitted"
        if len(self.upgraded) != 1:
            return "unknown", (
                f"cannot tell the system library's migration apart: upgraded libraries {self.upgraded or 'none'}, "
                f"submitted {self.submitted}"
            )
        system = self.upgraded[0]
        if system not in self.submitted:
            return "settled", f"the system library ({system}) submitted no background migration"
        if system in self.completed:
            return "settled", f"the system library's background migration {system} completed"
        return "waiting", f"the system library's background migration {system} is pending"


class StreamLines:
    """Whole lines from a raw pipe, blocking until one arrives. Read from the fd itself: a buffered reader can hold a
    complete line that `select` no longer reports, which would wait out the bound with the completion in hand."""

    def __init__(self, fd: int):
        self.fd = fd
        self.pending = b""
        self.sel = selectors.DefaultSelector()
        self.sel.register(fd, selectors.EVENT_READ)

    def next(self, deadline: float):
        """The next line (str, no newline), None at end of stream; TimeoutError past [deadline] (monotonic)."""
        while b"\n" not in self.pending:
            left = deadline - time.monotonic()
            if left <= 0 or not self.sel.select(timeout=left):
                raise TimeoutError
            chunk = os.read(self.fd, 65536)
            if not chunk:
                return None
            self.pending += chunk
        line, self.pending = self.pending.split(b"\n", 1)
        return line.decode("utf-8", "replace")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", required=True)
    parser.add_argument("--log", required=True)
    parser.add_argument("--bound", type=int, default=900, help="seconds before a missing completion FAILS the run")
    args = parser.parse_args()

    log = open(args.log, "a", buffering=1)
    state = Migrations()

    def take(line: str) -> None:
        log.write(line if line.endswith("\n") else line + "\n")
        state.feed(line)

    def result(status: str, why: str) -> int:
        took = time.monotonic() - started
        print(f"PHOTO-MIGRATION RESULT: {status} ({why}; {took:.0f} s)", flush=True)
        return 0 if status == "settled" else 1

    started = time.monotonic()
    log_cmd = ["xcrun", "simctl", "spawn", args.device, "log"]
    stream = subprocess.Popen(
        log_cmd + ["stream", "--style", "compact", "--predicate", PREDICATE],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0,
    )
    lines = StreamLines(stream.stdout.fileno())
    try:
        # `log stream` prints its "Filtering the log data using …" header once it is attached; only then is the past
        # read, so no event can fall between the two.
        header = lines.next(deadline=started + args.bound)
        if header is None:
            return result("failed", "log stream ended before it attached")
        log.write(header + "\n")
        past = subprocess.run(
            log_cmd + ["show", "--style", "compact", "--last", "1h", "--predicate", PREDICATE],
            capture_output=True, text=True,
        )
        if past.returncode != 0:
            return result("failed", f"log show exited {past.returncode}: {past.stderr.strip()[:200]}")
        for line in past.stdout.splitlines():
            take(line)

        status, why = state.verdict()
        while status == "waiting":
            try:
                line = lines.next(deadline=started + args.bound)
            except TimeoutError:
                return result("failed", f"{why} after {args.bound} s")
            if line is None:
                return result("failed", f"log stream ended while {why}")
            take(line)
            status, why = state.verdict()
        return result(status, why)
    finally:
        stream.kill()
        log.close()


if __name__ == "__main__":
    sys.exit(main())
