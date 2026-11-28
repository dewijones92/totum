#!/usr/bin/env python3
import json
import pathlib
import subprocess
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from github_runner_routes import ipv4_cidrs  # noqa: E402


class RunnerRoutesTest(unittest.TestCase):
    def test_only_the_runner_service_ranges_are_taken(self):
        meta = {"actions": ["4.175.114.51/32", "20.1.0.0/16"], "web": ["140.82.112.0/20"], "git": ["1.2.3.4/32"]}
        self.assertEqual(ipv4_cidrs(meta), ["4.175.114.51/32", "20.1.0.0/16"])

    def test_ipv6_is_left_out_because_the_tunnel_carries_ipv4_only(self):
        meta = {"actions": ["2603:1000::/24", "20.1.0.0/16"]}
        self.assertEqual(ipv4_cidrs(meta), ["20.1.0.0/16"])

    def test_a_huge_range_is_refused_so_the_tunnel_is_never_bypassed_wholesale(self):
        meta = {"actions": ["0.0.0.0/0", "0.0.0.0/1", "4.0.0.0/7", "4.0.0.0/8"]}
        self.assertEqual(ipv4_cidrs(meta), ["4.0.0.0/8"])

    def test_duplicates_and_garbage_are_dropped_and_the_order_is_stable(self):
        meta = {"actions": ["20.1.0.0/16", "4.0.0.5/32", "20.1.0.0/16", "not an address", ""]}
        self.assertEqual(ipv4_cidrs(meta), ["4.0.0.5/32", "20.1.0.0/16"])

    def test_a_missing_service_is_an_empty_list_not_an_error(self):
        self.assertEqual(ipv4_cidrs({}), [])

    def test_the_command_line_reads_the_api_answer_and_prints_one_range_per_line(self):
        script = pathlib.Path(__file__).resolve().parent / "github_runner_routes.py"
        meta = {"actions": ["20.1.0.0/16", "4.175.114.51/32"]}
        result = subprocess.run(
            [sys.executable, str(script)], input=json.dumps(meta), capture_output=True, text=True, check=True
        )
        self.assertEqual(result.stdout.split(), ["4.175.114.51/32", "20.1.0.0/16"])


if __name__ == "__main__":
    unittest.main()
