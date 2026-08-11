#!/usr/bin/env python3
"""Tests for test-summary.py.

A summariser that lies is worse than none: it is read exactly when something has gone wrong and
nobody is in a position to double-check it. So the shapes that matter are pinned here — including
the two that would be silently misleading (no results at all, and an unreadable file).

Run: python3 tools/ci/test_summary_test.py
"""
import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

_spec = importlib.util.spec_from_file_location("test_summary", Path(__file__).with_name("test-summary.py"))
summary = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(summary)

PASSING = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.GoodTest" tests="3" skipped="0" failures="0" errors="0" time="1.5">
  <testcase name="one" classname="com.example.GoodTest" time="0.5"/>
  <testcase name="two" classname="com.example.GoodTest" time="0.5"/>
  <testcase name="three" classname="com.example.GoodTest" time="0.5"/>
</testsuite>
"""

FAILING = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.BadTest" tests="2" skipped="0" failures="1" errors="0" time="0.4">
  <testcase name="fine" classname="com.example.BadTest" time="0.2"/>
  <testcase name="broken" classname="com.example.BadTest" time="0.2">
    <failure message="expected:&lt;1&gt; but was:&lt;2&gt;">java.lang.AssertionError</failure>
  </testcase>
</testsuite>
"""

# Instrumented runs nest suites inside <testsuites>, which is a different shape to parse.
NESTED = """<?xml version="1.0" encoding="UTF-8"?>
<testsuites tests="1" failures="1" errors="0" skipped="0">
  <testsuite name="com.example.DeviceTest" tests="1" failures="1" errors="0" skipped="0" time="9.0">
    <testcase name="onDevice" classname="com.example.DeviceTest" time="9.0">
      <failure message="no compose hierarchies">boom</failure>
    </testcase>
  </testsuite>
</testsuites>
"""

ERRORED = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.ErrTest" tests="1" skipped="0" failures="0" errors="1" time="0.1">
  <testcase name="exploded" classname="com.example.ErrTest" time="0.1">
    <error message="OutOfMemoryError">heap</error>
  </testcase>
</testsuite>
"""


class TestSummary(unittest.TestCase):

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.root = Path(self.folder.name)
        self.addCleanup(self.folder.cleanup)

    def write(self, name, text):
        path = self.root / name
        path.write_text(text, encoding="utf-8")
        return path

    def render(self, title="Unit tests"):
        return "\n".join(summary.render(title, summary.collect([str(self.root)])))

    def test_all_passing_reads_as_a_tick_and_the_right_totals(self):
        self.write("TEST-good.xml", PASSING)

        text = self.render()

        self.assertIn("✅", text)
        self.assertIn("**3 passed**", text)
        self.assertIn("**0 failed**", text)

    def test_a_failure_is_named_with_its_message(self):
        self.write("TEST-bad.xml", FAILING)

        text = self.render()

        self.assertIn("❌", text)
        self.assertIn("**1 failed**", text)
        self.assertIn("BadTest.broken", text)
        self.assertIn("expected:<1> but was:<2>", text)

    def test_an_error_counts_as_a_failure_and_is_listed(self):
        # A test that threw is not a test that passed, and JUnit records it separately.
        self.write("TEST-err.xml", ERRORED)

        text = self.render()

        self.assertIn("**1 failed**", text)
        self.assertIn("ErrTest.exploded", text)

    def test_nested_testsuites_are_read_too(self):
        # Instrumented results come back in this shape; missing it would report "no results"
        # for the entire emulator job.
        self.write("TEST-device.xml", NESTED)

        text = self.render("Instrumented")

        self.assertIn("**1 failed**", text)
        self.assertIn("DeviceTest.onDevice", text)

    def test_several_modules_are_added_together(self):
        self.write("TEST-good.xml", PASSING)
        self.write("TEST-bad.xml", FAILING)

        text = self.render()

        self.assertIn("**4 passed**", text)
        self.assertIn("**1 failed**", text)
        self.assertIn("2 classes", text)

    def test_no_results_at_all_says_so_instead_of_looking_green(self):
        # THE important one. A test task that silently stopped running must not render as a
        # clean pass — that is the worst possible green.
        text = self.render()

        self.assertIn("No test results found", text)
        self.assertNotIn("✅", text)

    def test_an_unreadable_file_does_not_hide_the_others(self):
        # A killed emulator leaves a truncated XML behind. The rest of the run still matters.
        self.write("TEST-truncated.xml", "<testsuite name=\"x\" tests=\"1\"")
        self.write("TEST-good.xml", PASSING)

        text = self.render()

        self.assertIn("**3 passed**", text)

    def test_a_long_failure_message_is_cut_rather_than_flooding_the_page(self):
        self.write("TEST-long.xml", FAILING.replace("expected:&lt;1&gt; but was:&lt;2&gt;", "x" * 5000))

        text = self.render()

        self.assertLess(len(text), 2000)

    def test_slow_classes_are_surfaced_when_there_is_something_slow(self):
        self.write("TEST-good.xml", PASSING)

        text = self.render()

        self.assertIn("Slowest classes", text)
        self.assertIn("GoodTest", text)

    def test_a_fast_run_does_not_bother_with_a_slowest_list(self):
        self.write("TEST-quick.xml", PASSING.replace('time="1.5"', 'time="0.02"').replace('time="0.5"', 'time="0.006"'))

        text = self.render()

        self.assertNotIn("Slowest classes", text)

    def test_it_never_fails_the_build_itself(self):
        # The test task is what decides whether a build is green. This only reports.
        #
        # GITHUB_STEP_SUMMARY is redirected, not merely tolerated: running these tests as a CI
        # step with the real variable set appended two bogus "No test results found" blocks to
        # the actual job summary — the tests writing into the thing they were testing.
        target = self.root / "summary.md"
        with mock.patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(target)}):
            self.assertEqual(0, summary.main(["Unit tests", str(self.root)]))
            self.assertEqual(0, summary.main([]))
            self.assertEqual(0, summary.main(["Unit tests", "/does/not/exist"]))

        self.assertIn("No test results found", target.read_text(encoding="utf-8"))

    def test_it_writes_to_the_job_summary_when_there_is_one(self):
        self.write("TEST-good.xml", PASSING)
        target = self.root / "job-summary.md"

        with mock.patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(target)}):
            summary.main(["Unit tests", str(self.root)])

        self.assertIn("**3 passed**", target.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
