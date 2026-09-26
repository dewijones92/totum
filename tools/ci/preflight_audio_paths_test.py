#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("preflight", ROOT / "tools/ci/preflight.py")
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)

MAIN = preflight.PLAYBACK_MAIN
GLOBS = [f"{MAIN}/Silence*.kt", f"{MAIN}/*Boost*.kt"]


class AudioQualityPathsTest(unittest.TestCase):

    def test_a_class_the_suite_imports_is_found(self):
        found = preflight.guarded_sources(["import com.dewijones92.totum.playback.SilenceCutter\n"], lambda path: True)
        self.assertEqual([f"{MAIN}/SilenceCutter.kt"], found)

    def test_a_name_with_no_file_of_its_own_is_skipped(self):
        found = preflight.guarded_sources(["import com.dewijones92.totum.playback.CutLevel\n"], lambda path: False)
        self.assertEqual([], found)

    def test_a_covered_source_passes(self):
        self.assertEqual([], preflight.uncovered_by([f"{MAIN}/LoudnessBoost.kt"], GLOBS))

    def test_a_new_source_outside_the_paths_is_caught(self):
        self.assertEqual([f"{MAIN}/Equaliser.kt"], preflight.uncovered_by([f"{MAIN}/Equaliser.kt"], GLOBS))


if __name__ == "__main__":
    unittest.main()
