#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("preflight", ROOT / "tools/ci/preflight.py")
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)

GLOBS = ["lib/exsurge/**", "app/src/*/java/com/dewijones92/totum/exsurge/**", "app/src/main/AndroidManifest.xml"]


class ExsurgePathsTest(unittest.TestCase):

    def test_any_file_named_for_exsurge_is_guarded(self):
        found = preflight.exsurge_sources({"app/src/main/res/raw/exsurge_go.ogg", "app/src/main/res/raw/other.ogg"})
        self.assertEqual(["app/src/main/res/raw/exsurge_go.ogg"], found)

    def test_docs_are_not_guarded(self):
        self.assertEqual([], preflight.exsurge_sources({"docs/features/exsurge-et-disce.md"}))

    def test_a_seam_it_leans_on_is_guarded(self):
        self.assertEqual(["app/src/main/AndroidManifest.xml"], preflight.exsurge_sources({"app/src/main/AndroidManifest.xml"}))

    def test_a_covered_source_passes(self):
        self.assertEqual([], preflight.uncovered_by(["app/src/test/java/com/dewijones92/totum/exsurge/A.kt"], GLOBS))

    def test_a_new_exsurge_file_outside_the_paths_is_caught(self):
        stray = "app/src/main/java/com/dewijones92/totum/ui/ExsurgeWidget.kt"
        self.assertEqual([stray], preflight.uncovered_by(preflight.exsurge_sources({stray}), GLOBS))


if __name__ == "__main__":
    unittest.main()
