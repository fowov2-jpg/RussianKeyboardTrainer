#!/usr/bin/env python3
"""Audit the actual compressed corpus, independently of variant selection."""
from __future__ import annotations

import argparse
import collections
import csv
import gzip
import hashlib
import json
from pathlib import Path

from build_russian_typos import NEIGHBORS, RU, SOURCES, blob_hash, decode_dictionary


def valid_edit(typo: str, word: str, kind: str) -> bool:
    if kind == "adjacent_substitution":
        changed = [i for i in range(len(word)) if word[i] != typo[i]] if len(typo) == len(word) else []
        return len(changed) == 1 and typo[changed[0]] in NEIGHBORS[word[changed[0]]]
    if kind == "missing_key":
        return len(typo) == len(word) - 1 and any(word[:i] + word[i + 1:] == typo for i in range(len(word)))
    if kind == "double_press":
        return len(typo) == len(word) + 1 and any(word[:i] + word[i] + word[i:] == typo for i in range(len(word)))
    if kind == "transposition":
        changed = [i for i in range(len(word)) if word[i] != typo[i]] if len(typo) == len(word) else []
        return (len(changed) == 2 and changed[1] == changed[0] + 1
                and typo[changed[0]] == word[changed[1]] and typo[changed[1]] == word[changed[0]])
    if kind == "neighbor_insertion":
        return len(typo) == len(word) + 1 and any(
            typo[:i + 1] + typo[i + 2:] == word and typo[i + 1] in NEIGHBORS[word[i]]
            for i in range(len(word)))
    if kind == "two_neighbor_errors":
        changed = [i for i in range(len(word)) if word[i] != typo[i]] if len(typo) == len(word) else []
        return (len(changed) == 2 and changed[1] == changed[0] + 1
                and all(typo[i] in NEIGHBORS[word[i]] for i in changed))
    return False


def audit(folder: Path, cache: Path, expected_words=100_000, per_word=10):
    words = (folder / "words-100000.txt").read_text(encoding="utf-8").splitlines()
    if len(words) != expected_words or len(set(words)) != expected_words or not all(RU.fullmatch(w) for w in words):
        raise ValueError("Incorrect word count, duplicate or invalid wordform")
    # Reconstruct the full exclusion vocabulary, rather than trusting metrics.
    texts = []
    for source, name in (("leeds", "leeds.txt"), ("hunspell", "ru_RU.dic")):
        raw = (cache / name).read_bytes()
        if blob_hash(raw) != SOURCES[source][1]:
            raise ValueError(f"Unverified source: {source}")
        texts.append(decode_dictionary(raw))
    known = {w.strip().lower() for w in texts[0].splitlines() if RU.fullmatch(w.strip().lower())}
    known.update(w.split("/", 1)[0].strip().lower() for w in texts[1].splitlines()[1:]
                 if RU.fullmatch(w.split("/", 1)[0].strip().lower()))
    wordset, seen, totals, rules = set(words), set(), collections.Counter(), collections.Counter()
    thirds = collections.Counter()
    path = folder / "typos-1000000.tsv.gz"
    with gzip.open(path, "rt", encoding="utf-8", newline="") as stream:
        reader = csv.reader(stream, delimiter="\t")
        if next(reader) != ["mistyped", "intended", "error_type"]:
            raise ValueError("Wrong TSV columns")
        for line, row in enumerate(reader, 2):
            if len(row) != 3:
                raise ValueError(f"Malformed row {line}")
            typo, word, kind = row
            if (word not in wordset or typo in seen or typo in known or typo == word
                    or not RU.fullmatch(typo) or not valid_edit(typo, word, kind)):
                raise ValueError(f"Invalid, duplicate or known-word error at row {line}: {row}")
            seen.add(typo)
            totals[word] += 1
            rules[kind] += 1
            if kind == "adjacent_substitution":
                position = next(i for i in range(len(word)) if typo[i] != word[i])
                thirds[min(2, position * 3 // len(word))] += 1
    if set(totals) != wordset or set(totals.values()) != {per_word}:
        raise ValueError("Each distinct word must have exactly ten distinct errors")
    result = {
        "status": "passed", "wordforms": len(wordset), "unique_typos": len(seen),
        "pairs_per_word": per_word, "by_rule": dict(rules),
        "neighbor_substitution_position_thirds": {
            name: thirds[i] for i, name in enumerate(("beginning", "middle", "end"))},
        "duplicate_typos": 0, "known_word_typos": 0, "invalid_edits": 0,
        "sha256": {name: hashlib.sha256((folder / name).read_bytes()).hexdigest()
                   for name in ("words-100000.txt", "typos-1000000.tsv.gz")},
    }
    metrics = json.loads((folder / "metrics.json").read_text(encoding="utf-8"))
    if (metrics["wordforms"] != len(wordset) or metrics["unique_typos"] != len(seen)
            or metrics["distinct_pairs"] != len(seen) or metrics["by_rule"] != dict(rules)):
        raise ValueError("Reported metrics differ from actual rows")
    (folder / "validation.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path("reports/lexicon"))
    parser.add_argument("--cache", type=Path, default=Path(".cache/lexicon"))
    args = parser.parse_args()
    print(json.dumps(audit(args.out, args.cache), ensure_ascii=False, indent=2))
