#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("preflight", ROOT / "tools/ci/preflight.py")
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)

RULE = "@get:Rule\nval compose = createComposeRule()\n"


class ComposeTestsOnWorkerThreadsTest(unittest.TestCase):

    def test_a_compose_test_with_a_default_dispatcher_scope_is_caught(self):
        source = RULE + "private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)\n"
        self.assertEqual(["Dispatchers.Default"], preflight.worker_dispatchers_in_compose_test(source))

    def test_an_io_dispatcher_is_caught_too(self):
        source = RULE + "val scope = CoroutineScope(Dispatchers.IO)\n"
        self.assertEqual(["Dispatchers.IO"], preflight.worker_dispatchers_in_compose_test(source))

    def test_a_main_dispatcher_scope_passes(self):
        source = RULE + "private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)\n"
        self.assertEqual([], preflight.worker_dispatchers_in_compose_test(source))

    def test_an_android_compose_rule_counts_as_a_compose_test(self):
        source = "val rule = createAndroidComposeRule<ComponentActivity>()\nval s = CoroutineScope(Dispatchers.Default)\n"
        self.assertEqual(["Dispatchers.Default"], preflight.worker_dispatchers_in_compose_test(source))

    def test_a_test_with_no_compose_rule_may_use_a_worker_scope(self):
        source = "private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)\n"
        self.assertEqual([], preflight.worker_dispatchers_in_compose_test(source))

    def test_a_mention_in_a_comment_is_not_code(self):
        source = RULE + "// not Dispatchers.Default: that resumes Compose on a worker\n"
        self.assertEqual([], preflight.worker_dispatchers_in_compose_test(source))


if __name__ == "__main__":
    unittest.main()
