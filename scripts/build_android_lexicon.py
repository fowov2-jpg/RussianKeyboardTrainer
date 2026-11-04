#!/usr/bin/env python3
"""Compile the audited corpus into a memory-mappable offline Android asset."""
from __future__ import annotations

import argparse
import collections
import csv
import gzip
import hashlib
import json
import shutil
import struct
from pathlib import Path

from build_russian_typos import NEIGHBORS, SOURCES, decode_dictionary, blob_hash, select_words

MAGIC = b"RSKLEX02"
KINDS = ("adjacent_substitution", "missing_key", "double_press", "transposition",
         "neighbor_insertion", "two_neighbor_errors")


def single_key_errors(word):
    """Supported physical single-edit model, including all neighboring inserts."""
    for i, char in enumerate(word):
        left, right = word[:i], word[i + 1:]
        yield left + right
        yield left + char + char + right
        for other in NEIGHBORS[char]:
            yield left + other + right
            yield left + other + char + right
            yield left + char + other + right
        if i + 1 < len(word) and char != word[i + 1]:
            yield left + word[i + 1] + char + word[i + 2:]


def write_table(stream, keys, values=None, flags=None):
    encoded = [word.encode("utf-8") for word in keys]
    stream.write(struct.pack(">I", len(encoded)))
    offset = 0
    for key in encoded:
        stream.write(struct.pack(">I", offset))
        offset += len(key)
    stream.write(struct.pack(">I", offset))
    if values is not None:
        for value in values:
            stream.write(struct.pack(">I", value))
        stream.write(bytes(flags))
    for key in encoded:
        stream.write(key)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=Path("reports/lexicon"))
    parser.add_argument("--cache", type=Path, default=Path(".cache/lexicon"))
    parser.add_argument("--out", type=Path, default=Path("android/app/src/main/assets/lexicon"))
    args = parser.parse_args()
    words = (args.corpus / "words-100000.txt").read_text(encoding="utf-8").splitlines()
    texts = []
    for name, filename in (("leeds", "leeds.txt"), ("hunspell", "ru_RU.dic")):
        raw = (args.cache / filename).read_bytes()
        if blob_hash(raw) != SOURCES[name][1]:
            raise ValueError(f"Dictionary hash mismatch: {name}")
        texts.append(decode_dictionary(raw))
    selected, known, _ = select_words(*texts)
    if words != selected:
        raise ValueError("Corpus words differ from pinned dictionaries")
    ids = {word: i for i, word in enumerate(words)}
    records = {}
    eligible = set()
    with gzip.open(args.corpus / "typos-1000000.tsv.gz", "rt", encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream, delimiter="\t"):
            typo, word, kind = row["mistyped"], row["intended"], row["error_type"]
            if typo in records or typo in known or word not in ids or kind not in KINDS:
                raise ValueError(f"Invalid corpus entry: {row}")
            records[typo] = (ids[word], KINDS.index(kind))
            # Frequency list order, not a fabricated probability score. Rare
            # words and two-error guesses remain explicit tap-to-apply choices.
            if ids[word] < 30_000 and kind != "two_neighbor_errors":
                eligible.add(typo)
    if len(records) != 1_000_000:
        raise ValueError("Expected exactly one million distinct error strings")
    initial = len(eligible)
    # Global uniqueness in the sampled corpus is insufficient. Check whether
    # another known spelling can produce the same typo with ONE physical edit.
    for i, word in enumerate(sorted(known)):
        for typo in single_key_errors(word):
            if typo in eligible and words[records[typo][0]] != word:
                eligible.remove(typo)
        if i % 100_000 == 0:
            print(f"Ambiguity check: {i}/{len(known)} known spellings", flush=True)
    args.out.mkdir(parents=True, exist_ok=True)
    keys = sorted(records)
    output = args.out / "russian.bin"
    temporary = output.with_suffix(".tmp")
    with temporary.open("wb") as stream:
        stream.write(MAGIC)
        write_table(stream, words)
        write_table(stream, sorted(known))
        write_table(stream, keys, [records[key][0] for key in keys],
                    [(records[key][1] << 1) | int(key in eligible) for key in keys])
    temporary.replace(output)
    stats = {
        "format": MAGIC.decode(), "wordforms": len(words), "protected_spellings": len(known),
        "indexed_typos": len(keys), "automatic_single_edit_candidates": len(eligible),
        "ambiguous_single_edits_excluded_from_automatic": initial - len(eligible),
        "automatic_frequency_rank_limit": 30_000,
        "bytes": output.stat().st_size, "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "corpus_sha256": hashlib.sha256((args.corpus / "typos-1000000.tsv.gz").read_bytes()).hexdigest(),
        "sources": json.loads((args.corpus / "metrics.json").read_text(encoding="utf-8"))["sources"],
        "scope": "Synthetic candidates; single-edit ambiguity checked against pinned sources and keyboard model only",
    }
    (args.out / "manifest.json").write_text(json.dumps(stats, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for name in ("leeds-LICENSE.md", "hunspell-LICENSE.txt"):
        shutil.copyfile(args.corpus / "sources" / name, args.out / name)
    (args.out / "NOTICE.txt").write_text(
        "Russian keyboard lexicon: 100,000 selected wordforms; 1,000,000 synthetic typo candidates.\n"
        "University of Leeds Corpus, editing by hingston/russian, CC BY 2.5.\n"
        "Russian Hunspell Dictionary, copyright Valery Ledovskoy, MPL 2.0.\n"
        "Exact source URLs, revisions and hashes: manifest.json. Original licenses included.\n"
        "Synthetic edits, candidate selection and binary compilation: this project.\n", encoding="utf-8")
    print(json.dumps(stats, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
