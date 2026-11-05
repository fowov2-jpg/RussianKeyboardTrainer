#!/usr/bin/env python3
"""Fetch public source corpora for REPRODUCIBLE OFFLINE preparation.

The APK never executes this code or uses internet access. Raw data stays in
ignored data/raw/, not committed or shipped. Inspect the license notes in
CORPUS_DATA.md before any redistribution.

Usage:
  python tools/fetch_corpora.py --sources gec rlc
  python tools/fetch_corpora.py --sources gera --evaluation-only
  python tools/fetch_corpora.py --sources social --noncommercial-research
"""
import argparse
from pathlib import Path
from urllib.request import Request, urlopen

SOURCES = {
    "gec": [
        ("russian_gec.csv",
         "https://raw.githubusercontent.com/dreuxx/Russian-Grammar-Error-Correction-Dataset/main/russian_gec_dataset_final%20%281%29.csv")
    ],
    "rlc": [
        ("rlc_annotations.csv",
         "https://raw.githubusercontent.com/Russian-Learner-Corpus/rlc-annotated/main/annotations.csv"),
        ("rlc_test.csv",
         "https://raw.githubusercontent.com/Russian-Learner-Corpus/rlc-annotated/main/rlc_test.csv")
    ],
    "gera": [
        ("GERA.dev.m2",
         "https://raw.githubusercontent.com/ReginaNasyrova/GERA/main/GERA.dev.m2"),
        ("GERA.test.m2",
         "https://raw.githubusercontent.com/ReginaNasyrova/GERA/main/GERA.test.m2")
    ],
    "social": [
        ("normalization.json",
         "https://huggingface.co/datasets/ruscorpora/normalization/resolve/main/normalization.json")
    ],
}

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--sources", nargs="+", choices=list(SOURCES), required=True)
    p.add_argument("--output", type=Path, default=Path("data/raw"))
    p.add_argument("--evaluation-only", action="store_true",
                   help="Acknowledge GERA has no explicit redistribution license")
    p.add_argument("--noncommercial-research", action="store_true",
                   help="Acknowledge social corpus CC BY-NC-SA 4.0 and its restrictions")
    args = p.parse_args()
    if "gera" in args.sources and not args.evaluation_only:
        p.error("GERA requires --evaluation-only; never bundle without permission.")
    if "social" in args.sources and not args.noncommercial_research:
        p.error("Social normalization requires --noncommercial-research.")
    args.output.mkdir(parents=True, exist_ok=True)
    for source in args.sources:
        for filename, url in SOURCES[source]:
            target = args.output / filename
            print(f"Downloading {source}: {filename}")
            req = Request(url, headers={"User-Agent": "OfflineSmartKeyboard-CorpusResearch/0.16"})
            with urlopen(req, timeout=90) as stream, target.open("wb") as dest:
                while True:
                    chunk = stream.read(512 * 1024)
                    if not chunk:
                        break
                    dest.write(chunk)
            print(f"Saved {target}: {target.stat().st_size} bytes (raw data, NOT in APK)")
    if "social" in args.sources:
        print("WARNING: Verify the Hugging Face Files tab for the current JSON name;"
              " social is opt-in, and cannot be redistributed in the APK.")

if __name__ == "__main__":
    main()
