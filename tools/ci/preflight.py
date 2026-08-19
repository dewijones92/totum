#!/usr/bin/env python3
"""Runs the parts of CI that the local gate cannot see, BEFORE a push.

Dewi, 2026-08-18: *"shift left to catch stuff faster"*. Two red builds in a row that day were both
invisible to `./gradlew detekt lint test koverVerify` — because neither was about Kotlin. One was a live
test missing from a CI list; the other was a shell variable that does not survive
android-emulator-runner, which runs EACH LINE of its `script:` in a separate shell. Each cost an
eleven-minute round trip to discover something a local check answers in under a second.

Checks, cheapest first:

1. `ci.yml` parses as YAML at all.
2. Every live instrumented test is registered (delegates to check-live-tests-registered.py).
3. The live-test list parses non-empty **through the same one-line POSIX sh expansion CI uses** — an
   empty filter excludes nothing and reports success having tested nothing.
4. No line of an `android-emulator-runner` `script:` depends on a shell variable assigned by an EARLIER
   line. This is the bug class, not just the instance: the pattern is correct in an ordinary `run:` block
   and silently wrong here.
5. Every `script:`/`run:` block is valid POSIX shell (`sh -n`).
6. The plain-Python test suites pass — no Gradle task knows they exist.
"""
import pathlib
import re
import subprocess
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/ci.yml"
LIVE_LIST = ROOT / "tools/ci/live-instrumented-tests.txt"
EMULATOR_ACTION = "reactivecircus/android-emulator-runner"

# A bare `NAME=` assignment at the start of a line, which is what does not survive the runner.
ASSIGNMENT = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)=")


def fail(message: str) -> int:
    print(f"FAIL: {message}")
    return 1


def steps(workflow: dict):
    for job in (workflow.get("jobs") or {}).values():
        for step in job.get("steps") or []:
            yield step


def check_yaml() -> tuple[int, dict]:
    try:
        return 0, yaml.safe_load(WORKFLOW.read_text())
    except yaml.YAMLError as error:
        return fail(f"{WORKFLOW.name} does not parse: {error}"), {}


def check_live_list_expands() -> int:
    """Through `sh`, one line, exactly as the workflow does it — not bash, where `\\s` would work."""
    expansion = (
        f"grep -vE '^[[:space:]]*(#|$)' {LIVE_LIST.relative_to(ROOT)} | paste -sd,"
    )
    result = subprocess.run(["sh", "-c", expansion], cwd=ROOT, capture_output=True, text=True)
    value = result.stdout.strip()
    if not value:
        return fail(
            f"the live-test list expands to NOTHING under sh: {expansion}\n"
            "      An empty filter excludes nothing (CI) or runs nothing (live phase), and both\n"
            "      report success having tested nothing."
        )
    count = len(value.split(","))
    print(f"  ok: live-test list expands to {count} classes under one-line sh")
    return 0


def check_no_cross_line_variables(workflow: dict) -> int:
    """The 2026-08-18 bug: `LIVE=$(...)` on one line, `$LIVE` on the next, in an emulator-runner script."""
    problems = 0
    for step in steps(workflow):
        if EMULATOR_ACTION not in str(step.get("uses", "")):
            continue
        script = (step.get("with") or {}).get("script") or ""
        assigned: dict[str, int] = {}
        for number, line in enumerate(script.splitlines(), start=1):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            for name, where in assigned.items():
                if re.search(rf"\${{?{name}\b", stripped):
                    problems += fail(
                        f"{EMULATOR_ACTION} script line {number} uses ${name}, assigned on line {where}.\n"
                        "      Each line of `script:` runs in its OWN shell, so the value is empty here.\n"
                        "      Inline the substitution on the line that needs it."
                    )
            match = ASSIGNMENT.match(line)
            if match:
                assigned[match.group(1)] = number
    if not problems:
        print("  ok: no emulator-runner script line depends on an earlier line's variable")
    return problems


# DISCOVERED, not listed. The hand-kept version was already incomplete on the day it was written --
# it omitted tools/ci/test_summary_test.py, which CI runs -- while its docstring claimed to cover "the
# plain-Python test suites". That is the exact drift this file exists to prevent, on this file.
PYTHON_TEST_GLOBS = [
    "tools/ci/*_test.py",
    "**/src/test/python/*_test.py",
]


def check_python_tests() -> int:
    """Runs the plain-Python suites, which no Gradle task knows about.

    The yt-dlp bridge's note collector is plain Python and had NO test, which is how a filter that was
    exactly backwards shipped: it kept every routine progress line and logged a ~1KB WARN on every
    resolve, flooding a bounded report buffer. Sub-second, so it belongs here rather than in the gate.
    """
    problems = 0
    found = sorted({p for pattern in PYTHON_TEST_GLOBS for p in ROOT.glob(pattern)})
    if not found:
        return fail("no python test suites found — the globs no longer match anything")
    for path in found:
        relative = path.relative_to(ROOT)
        result = subprocess.run([sys.executable, str(path)], cwd=ROOT, capture_output=True, text=True)
        if result.returncode != 0:
            problems += fail(f"{relative} failed:\n{result.stdout.strip()}\n{result.stderr.strip()}")
        else:
            ran = [line for line in result.stderr.splitlines() if line.startswith("Ran ")]
            print(f"  ok: {relative} — {ran[0] if ran else 'passed'}")
    return problems


def check_shell_syntax(workflow: dict) -> int:
    problems = 0
    for step in steps(workflow):
        body = (step.get("with") or {}).get("script") or step.get("run") or ""
        if not body.strip():
            continue
        # GitHub expressions are not shell; blank them so `sh -n` judges the shell alone.
        cleaned = re.sub(r"\$\{\{[^}]*\}\}", "PLACEHOLDER", body)
        result = subprocess.run(["sh", "-n"], input=cleaned, capture_output=True, text=True)
        if result.returncode != 0:
            problems += fail(
                f"step {step.get('name', '<unnamed>')!r} is not valid POSIX shell:\n"
                f"      {result.stderr.strip()}"
            )
    if not problems:
        print("  ok: every script/run block is valid POSIX shell")
    return problems


def main() -> int:
    print(f"preflight: {WORKFLOW.relative_to(ROOT)}")
    problems, workflow = check_yaml()
    if problems:
        return problems
    print("  ok: ci.yml parses")
    registered = subprocess.run(
        [sys.executable, str(ROOT / "tools/ci/check-live-tests-registered.py")],
        capture_output=True, text=True,
    )
    if registered.returncode != 0:
        print(registered.stdout.rstrip())
        problems += 1
    else:
        print("  ok: all live instrumented tests are registered")
    problems += check_live_list_expands()
    problems += check_python_tests()
    problems += check_no_cross_line_variables(workflow)
    problems += check_shell_syntax(workflow)
    if problems:
        print(f"\npreflight FAILED with {problems} problem(s) — none of these would show up in the Gradle gate.")
        return 1
    print("preflight passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
