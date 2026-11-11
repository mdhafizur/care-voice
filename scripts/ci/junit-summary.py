#!/usr/bin/env python3
"""Writes a Markdown summary of JUnit XML test results to the GitHub job summary ($GITHUB_STEP_SUMMARY).

Usage: junit-summary.py <title> [glob ...]   (default glob: **/build/test-results/test/*.xml)

One line per module with passed / failed / skipped counts, then each failed test with the first line of its error,
so a red run shows what broke on the run page instead of only in a 1,500-line log. Only the Python standard library.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

MAX_FAILURES = 50


def main() -> None:
    title = sys.argv[1] if len(sys.argv) > 1 else "Tests"
    patterns = sys.argv[2:] or ["**/build/test-results/test/*.xml"]
    files = sorted({path for pattern in patterns for path in glob.glob(pattern, recursive=True)})

    totals = defaultdict(lambda: {"passed": 0, "failed": 0, "skipped": 0})
    failures = []
    for path in files:
        module = path.split(os.sep)[0]
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                totals[module]["failed"] += 1
                message = (problem.get("message") or problem.text or "").strip().splitlines()
                first_line = message[0][:200] if message else ""
                short_class = case.get("classname", "").rsplit(".", 1)[-1]
                failures.append(f"| {module} | {short_class} | {case.get('name', '')} | {first_line.replace('|', '/')} |")
            elif case.find("skipped") is not None:
                totals[module]["skipped"] += 1
            else:
                totals[module]["passed"] += 1

    lines = [f"## {title}", ""]
    if not totals:
        lines.append("No test results found.")
    else:
        failed = sum(t["failed"] for t in totals.values())
        lines.append(f"**{'❌ ' + str(failed) + ' failed' if failed else '✅ all passed'}**")
        lines += ["", "| Module | Passed | Failed | Skipped |", "|---|---:|---:|---:|"]
        for module, t in sorted(totals.items()):
            lines.append(f"| {module} | {t['passed']} | {t['failed']} | {t['skipped']} |")
        if failures:
            lines += ["", "### Failed tests", "", "| Module | Class | Test | Error |", "|---|---|---|---|"]
            lines += failures[:MAX_FAILURES]
            if len(failures) > MAX_FAILURES:
                lines.append(f"| … | | {len(failures) - MAX_FAILURES} more | see the test-reports artifact |")

    text = "\n".join(lines) + "\n"
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as summary:
            summary.write(text)
    else:
        print(text)


if __name__ == "__main__":
    main()
