#!/usr/bin/env python3
"""Require individually executed Android unit cases, not a loop disguised as 1 test."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
from xml.etree import ElementTree as ET


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reports", type=Path, required=True)
    ap.add_argument("--minimum", type=int, default=10000)
    ap.add_argument("--summary", type=Path)
    args = ap.parse_args()

    xml_files = list(args.reports.rglob("TEST-*RussianCorrectionCorpusTest*.xml"))
    if not xml_files:
        sys.exit("ERROR: individual RussianCorrectionCorpusTest JUnit XML missing")
    cases = []
    for path in xml_files:
        root = ET.parse(path).getroot()
        cases.extend(root.findall(".//testcase"))
    failed = [x for x in cases if any(x.find(tag) is not None for tag in ("failure", "error", "skipped"))]
    identifiers = [x.attrib.get("name", "") for x in cases]
    if len(cases) < args.minimum or failed or len(set(identifiers)) != len(identifiers):
        sys.exit(
            f"ERROR: Corpus cases={len(cases)}, failed/skipped={len(failed)}, "
            f"distinct={len(set(identifiers))}, required={args.minimum}"
        )
    result = f"**Russian word/sentence regression: {len(cases)} separate JUnit cases PASSED**"
    print(result)
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as f:
            f.write(f"### Android word/sentence regression\n{result}\n")


if __name__ == "__main__":
    main()
