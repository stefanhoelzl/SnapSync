#!/usr/bin/env python3
"""Tests for the photo-library migration gate (`scripts/await-photo-migration.py`).

Stdlib `unittest` only, like the gate. Run: `python3 scripts/await_photo_migration_test.py`. The log lines are
`assetsd`'s own, verbatim from run 37004211450 (`log … --style compact`), trimmed only where they ran on.
"""

import importlib.util
import os
import pathlib
import threading
import time
import unittest

_spec = importlib.util.spec_from_file_location(
    "await_photo_migration", pathlib.Path(__file__).with_name("await-photo-migration.py")
)
gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gate)

SYSTEM = "com.apple.assetsd.migration.16777230.3098355"
SYNDICATION = "com.apple.assetsd.migration.16777230.3140993"

STAT = (
    "2026-10-02 12:07:49.039 Df assetsd[17291:c9a9] [com.apple.photos.backend:Migration] tag:37AE79AF:sc      "
    "stat: st_dev=16777230 st_ino=3098355 st_mode=16893 (drwxrwxr-x) st_nlink=6 st_uid=501 st_gid=20 st_rdev=0"
)
SUBMIT_SYSTEM = (
    "2026-10-02 12:07:53.681 Df assetsd[17291:c9a9] [com.apple.photos.backend:Backend] Submitting task request for "
    f"background migration with identifier {SYSTEM}"
)
SUBMIT_SYNDICATION = (
    "2026-10-02 12:08:32.594 Df assetsd[17291:d0a1] [com.apple.photos.backend:Backend] Submitting task request for "
    f"background migration with identifier {SYNDICATION}"
)
RESUBMIT_SYSTEM = (
    "2026-10-02 12:10:23.473 Df assetsd[17291:f46e] [com.apple.BackgroundSystemTasks:BGSTFramework] Cancelling and "
    f"resubmitting {SYSTEM} due to a failed launch acknowledgment"
)
EXPIRE_SYNDICATION = (
    "2026-10-02 12:10:23.952 Df assetsd[17291:f190] [com.apple.BackgroundSystemTasks:BGSTFramework] Client requested "
    f"expiration of task <BGNonRepeatingSystemTask: {SYNDICATION}>"
)
COMPLETE_SYSTEM = (
    "2026-10-02 12:12:03.270 Df assetsd[17291:f190] [com.apple.BackgroundSystemTasks:BGSTFramework] Marking task "
    f"<BGNonRepeatingSystemTask: {SYSTEM}> complete"
)


def state_after(*lines):
    s = gate.Migrations()
    for line in lines:
        s.feed(line)
    return s.verdict()[0]


class MigrationsTest(unittest.TestCase):
    def test_the_measured_run_waits_until_the_system_library_completes(self):
        before = [STAT, SUBMIT_SYSTEM, SUBMIT_SYNDICATION, RESUBMIT_SYSTEM, EXPIRE_SYNDICATION]
        self.assertEqual("waiting", state_after(*before))
        self.assertEqual("settled", state_after(*before, COMPLETE_SYSTEM))

    def test_a_cancelled_and_resubmitted_task_is_not_a_completion(self):
        self.assertEqual("waiting", state_after(STAT, SUBMIT_SYSTEM, RESUBMIT_SYSTEM))

    def test_the_syndication_library_is_never_awaited(self):
        self.assertEqual("settled", state_after(STAT, SUBMIT_SYSTEM, SUBMIT_SYNDICATION, COMPLETE_SYSTEM))

    def test_a_completion_seen_before_its_submission_still_counts(self):
        # `log show` and `log stream` overlap; the order the gate reads them in is not the order they happened.
        self.assertEqual("settled", state_after(COMPLETE_SYSTEM, STAT, SUBMIT_SYSTEM))

    def test_nothing_submitted_is_nothing_pending(self):
        self.assertEqual("settled", state_after())
        self.assertEqual("settled", state_after(STAT))

    def test_a_system_library_that_submitted_nothing_is_settled(self):
        self.assertEqual("settled", state_after(STAT, SUBMIT_SYNDICATION))

    def test_submissions_with_no_upgraded_library_fail_rather_than_guess(self):
        self.assertEqual("unknown", state_after(SUBMIT_SYSTEM, SUBMIT_SYNDICATION))

    def test_two_upgraded_libraries_fail_rather_than_guess(self):
        other = STAT.replace("st_ino=3098355", "st_ino=4000000")
        self.assertEqual("unknown", state_after(STAT, other, SUBMIT_SYSTEM))

    def test_a_repeated_stat_line_is_one_library(self):
        # The upgrade logs its stat line once per schema stage: five times in the measured run.
        self.assertEqual("waiting", state_after(STAT, STAT, STAT, SUBMIT_SYSTEM))


class StreamLinesTest(unittest.TestCase):
    def setUp(self):
        self.r, self.w = os.pipe()
        self.lines = gate.StreamLines(self.r)

    def tearDown(self):
        os.close(self.r)
        try:
            os.close(self.w)
        except OSError:
            pass

    def test_lines_that_arrive_in_one_write_are_each_returned_without_another_read(self):
        os.write(self.w, b"one\ntwo\n")
        deadline = time.monotonic() + 1
        self.assertEqual("one", self.lines.next(deadline))
        # Nothing more will be written: a reader that waited on the pipe here would time out.
        self.assertEqual("two", self.lines.next(deadline))

    def test_a_line_split_across_writes_is_joined(self):
        def later():
            os.write(self.w, b"Marking task <BGNonRep")
            time.sleep(0.05)
            os.write(self.w, b"eatingSystemTask: x> complete\n")

        threading.Thread(target=later).start()
        self.assertEqual("Marking task <BGNonRepeatingSystemTask: x> complete", self.lines.next(time.monotonic() + 5))

    def test_end_of_stream_is_none(self):
        os.close(self.w)
        self.assertIsNone(self.lines.next(time.monotonic() + 1))

    def test_silence_past_the_deadline_raises(self):
        with self.assertRaises(TimeoutError):
            self.lines.next(time.monotonic() + 0.05)


if __name__ == "__main__":
    unittest.main()
