"""Fail CI unless the Android ONNX instrumentation test actually PASSED.

Gradle considers JUnit skipped tests successful, so this postcondition is
essential for catching accidentally omitted test model assets.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

TARGET = "quantizedOnnxScoresMatchPythonOnTheEmulator"
CLASS = "dev.russiankeyboard.prototype.OnDeviceRankerTest"


def assert_android_onnx_pass(results: Path) -> int:
    xml_files = sorted(results.rglob("TEST-*.xml")) if results.exists() else []
    if not xml_files:
        raise AssertionError(f"No Android instrumentation XML reports found under {results}")

    matching = []
    for path in xml_files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            raise AssertionError(f"Invalid JUnit report: {path}") from exc
        for case in root.iter("testcase"):
            classname = case.attrib.get("classname", "")
            name = case.attrib.get("name", "")
            if classname.endswith(CLASS) and name == TARGET:
                matching.append((path, case))

    if len(matching) != 1:
        raise AssertionError(
            f"Expected exactly one {CLASS}.{TARGET}, found {len(matching)} "
            f"in {len(xml_files)} XML reports"
        )
    path, case = matching[0]
    if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
        raise AssertionError(f"ONNX test was skipped or failed in {path}")
    print(f"PASS: {CLASS}.{TARGET} completed on Android — {path}")
    return 1


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    args = parser.parse_args()
    try:
        assert_android_onnx_pass(args.results)
    except AssertionError as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
