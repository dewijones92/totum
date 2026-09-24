#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("preflight", ROOT / "tools/ci/preflight.py")
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


class DexIllegalTestNamesTest(unittest.TestCase):

    def test_an_apostrophe_is_caught(self):
        self.assertEqual(["the player's menu"], preflight.dex_illegal_test_names("fun `the player's menu`() {}"))

    def test_a_comma_is_caught(self):
        self.assertEqual(["offline, it skips"], preflight.dex_illegal_test_names("fun `offline, it skips`() {}"))

    def test_a_plain_name_passes(self):
        self.assertEqual([], preflight.dex_illegal_test_names("fun `the player menu goes to the podcast`() {}"))

    def test_a_backticked_identifier_that_is_not_a_function_is_ignored(self):
        self.assertEqual([], preflight.dex_illegal_test_names("val x = `it's`\n"))


if __name__ == "__main__":
    unittest.main()
