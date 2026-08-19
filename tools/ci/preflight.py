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
MANUAL_MARKER = "MANUAL ONLY"
WORKFLOW = ROOT / ".github/workflows/ci.yml"
LIVE_LIST = ROOT / "tools/ci/live-instrumented-tests.txt"
LIVE_RUNNER = ROOT / "tools/ci/live-test-via-home.sh"
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
    """
    Runs the REAL expansions, lifted out of ci.yml and live-test-via-home.sh rather than copied.

    A copy drifts, and this one did within the hour: the `manual:` prefix landed on 2026-08-19 and this
    check went on exercising the previous command, so it would have passed while ci.yml excluded a class
    literally named `manual:com.dewijones92...` — which matches nothing, meaning the very tests it meant
    to exclude would have run in the ordinary job anyway. A guard that quietly stops guarding the thing
    it is named after is worse than no guard.
    """
    ci = re.search(r"L=\$\((grep -vE .*?)\); test", WORKFLOW.read_text())
    runner = re.search(r"LIVE=\$\((grep -vE [^\n]*?)\)\n", LIVE_RUNNER.read_text())
    if not ci or not runner:
        return fail("could not find the live-list expansion in ci.yml or live-test-via-home.sh")

    here = str(LIVE_LIST)
    excluded = run_sh(re.sub(r'"?\$\(dirname "\$0"\)/live-instrumented-tests.txt"?|tools/ci/live-instrumented-tests.txt', here, ci.group(1)))
    live = run_sh(re.sub(r'"?\$\(dirname "\$0"\)/live-instrumented-tests.txt"?', here, runner.group(1)))
    if not excluded or not live:
        return fail(
            "a live-list expansion came back EMPTY.\n"
            "      An empty filter excludes nothing (CI) or runs nothing (live phase), and both report\n"
            "      success having tested nothing."
        )
    problems = 0
    if any(":" in name for name in excluded.split(",")):
        problems += fail(
            "ci.yml's exclusion still carries a `manual:` prefix, so it names classes that do not exist\n"
            "      — and the tests it meant to exclude would run in the ordinary job."
        )
    manual = [l.removeprefix("manual:") for l in LIVE_LIST.read_text().splitlines() if l.startswith("manual:")]
    if any(m in live.split(",") for m in manual):
        problems += fail("the live phase would run a `manual:` test, which no unattended run can pass")
    if problems == 0:
        print(f"  ok: {len(excluded.split(','))} excluded from CI, {len(live.split(','))} run in the live phase")
    return problems


def run_sh(command: str) -> str:
    """Through `sh`, one line, exactly as the workflow does it — not bash, where `\\s` would work."""
    return subprocess.run(["sh", "-c", command], cwd=ROOT, capture_output=True, text=True).stdout.strip()


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


def check_manual_tests_are_marked() -> int:
    """
    A test no unattended run can pass must be `manual:` in the live list, and vice versa.

    Both directions, because both have cost. On 2026-08-19 `SignInOnThisDeviceTest` — which waits NINE
    MINUTES for a person to type a code at google.com/device — was registered as an ordinary live test,
    so CI ran it, failed it every time, and spent most of that wait doing so. The reverse is just as
    bad: marking something `manual:` that could run unattended silently drops its coverage, and nothing
    would ever say so.

    The declaration lives in the test, next to the reason, rather than in the list: a list entry is
    easy to copy without noticing, and a source file is where someone editing the test will see it.
    """
    listed = LIVE_LIST.read_text().splitlines()
    entries = [line.strip() for line in listed if line.strip() and not line.strip().startswith("#")]
    problems = 0
    for entry in entries:
        marked_in_list = entry.startswith("manual:")
        klass = entry.removeprefix("manual:")
        found = list(ROOT.glob(f"app/src/androidTest/**/{klass.rsplit('.', 1)[-1]}.kt"))
        if not found:
            print(f"  PROBLEM: {klass} is in the live list but no such test file exists")
            print("    A name that matches nothing filters nothing, and reports success having run less.")
            problems += 1
            continue
        declared = MANUAL_MARKER in found[0].read_text()
        if declared and not marked_in_list:
            print(f"  PROBLEM: {klass} declares {MANUAL_MARKER} but is not `manual:` in the live list")
            print("    CI will run it, and it cannot pass unattended.")
            problems += 1
        elif marked_in_list and not declared:
            print(f"  PROBLEM: {klass} is `manual:` in the live list but does not declare {MANUAL_MARKER}")
            print(f"    Say why in the test itself, or drop the prefix so its coverage actually runs.")
            problems += 1
    if not problems:
        manual = sum(1 for e in entries if e.startswith("manual:"))
        print(f"  ok: live list agrees with the tests ({len(entries) - manual} run live, {manual} manual-only)")
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
    problems += check_manual_tests_are_marked()
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
