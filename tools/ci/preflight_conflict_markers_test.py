#!/usr/bin/env python3
"""The conflict-marker guard and the Python suite discovery, both in preflight.py.

Marker lines are built from pieces here, because a line of this file that began with them would fail
the very guard under test.
"""
import importlib.util
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("preflight", ROOT / "tools/ci/preflight.py")
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)

OPEN = "<" * 7 + " HEAD"
SPLIT = "=" * 7
CLOSE = ">" * 7 + " worktree-agent-1"
BASE = "|" * 7 + " merged common ancestors"


def lines(*rows: str) -> str:
    return "\n".join(rows) + "\n"


class ConflictMarkersTest(unittest.TestCase):

    def test_a_whole_conflict_is_three_markers(self):
        text = lines("# ADRs", OPEN, "| 1 | ours |", SPLIT, "| 1 | theirs |", CLOSE, "tail")

        self.assertEqual([(2, "<<<<<<<"), (4, "======="), (6, ">>>>>>>")], preflight.conflict_markers(text))

    def test_a_markdown_underline_outside_a_conflict_is_not_a_marker(self):
        text = lines("Title", SPLIT, "", "Subtitle", SPLIT)

        self.assertEqual([], preflight.conflict_markers(text))

    def test_an_underline_after_a_resolved_conflict_is_not_a_marker_either(self):
        text = lines(OPEN, "a", SPLIT, "b", CLOSE, "Heading", SPLIT)

        self.assertEqual([(1, "<<<<<<<"), (3, "======="), (5, ">>>>>>>")], preflight.conflict_markers(text))

    def test_an_opener_or_closer_alone_is_caught(self):
        self.assertEqual([(1, "<<<<<<<")], preflight.conflict_markers(lines(OPEN, "x")))
        self.assertEqual([(2, ">>>>>>>")], preflight.conflict_markers(lines("x", CLOSE)))

    def test_a_diff3_base_section_is_caught(self):
        text = lines(OPEN, "a", BASE, "o", SPLIT, "b", CLOSE)

        self.assertEqual(
            ["<<<<<<<", "|||||||", "=======", ">>>>>>>"], [marker for _, marker in preflight.conflict_markers(text)]
        )

    def test_markers_that_are_not_at_the_start_of_a_line_are_prose(self):
        text = lines("git writes " + OPEN + " and " + CLOSE, "    " + OPEN, "a " + SPLIT)

        self.assertEqual([], preflight.conflict_markers(text))

    def test_fewer_than_seven_characters_is_not_a_marker(self):
        self.assertEqual([], preflight.conflict_markers(lines("<<<<<< HEAD", "=====", ">>>>>> x")))

    def test_windows_line_endings_are_read_too(self):
        text = OPEN + "\r\na\r\n" + SPLIT + "\r\nb\r\n" + CLOSE + "\r\n"

        self.assertEqual(3, len(preflight.conflict_markers(text)))

    def test_the_repo_is_clean_today(self):
        for path in preflight.repo_text_files():
            self.assertEqual([], preflight.conflict_markers(path.read_text(errors="replace")), str(path))


class PythonSuiteDiscoveryTest(unittest.TestCase):

    def touch(self, root: pathlib.Path, relative: str) -> str:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("")
        return relative

    def discovered(self, root: pathlib.Path) -> list:
        return [str(p.relative_to(root)) for p in preflight.discover_python_tests(root)]

    def test_server_suites_are_found_at_any_depth(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            wanted = [
                self.touch(root, "server/top_test.py"),
                self.touch(root, "server/totum-auth/server_test.py"),
                self.touch(root, "server/a/b/c/deep_test.py"),
            ]
            self.touch(root, "server/totum-auth/server.py")

            self.assertEqual(sorted(wanted), self.discovered(root))

    def test_handoff_suites_are_found_at_any_depth(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            wanted = [
                self.touch(root, "tools/handoff/hermes-audio/test_audio_ctl.py"),
                self.touch(root, "tools/handoff/hermes-audio/sub/test_nested.py"),
            ]
            self.touch(root, "tools/handoff/hermes-audio/audio-ctl.py")

            self.assertEqual(sorted(wanted), self.discovered(root))

    def test_a_directory_named_like_a_test_is_not_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "server/x_test.py").mkdir(parents=True)

            self.assertEqual([], self.discovered(root))

    def test_the_real_repo_finds_the_suites_that_were_once_at_risk_of_being_skipped(self):
        found = {str(p.relative_to(ROOT)) for p in preflight.discover_python_tests(ROOT)}

        self.assertIn("server/totum-auth/server_test.py", found)
        self.assertIn("server/totum-cli/chromecast_helper_test.py", found)
        self.assertIn("tools/handoff/hermes-audio/test_audio_ctl.py", found)
        self.assertIn("tools/handoff/hermes-audio/test_audio_ctl_contract.py", found)


if __name__ == "__main__":
    unittest.main()
