#!/usr/bin/env python3
"""Reproducible OFFLINE QA data: 100k dictionary wordforms, 1m synthetic typos.

The output is NOT a trained spelling model or permission to auto-replace text.
Two pinned licensed public dictionaries are downloaded for CI data generation.
"""
from __future__ import annotations
import argparse
import collections
import gzip
import hashlib
import io
import itertools
import json
import re
import time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

TARGET = 100_000
PER_WORD = 10
SOURCES = {
    "leeds": ("https://raw.githubusercontent.com/hingston/russian/b144235f90fc720ef310f304e31acc3825d62a1b/100000-russian-words.txt",
              "a121c5a297bb174752eb4251984f86a57f49e24e", "CC BY 2.5"),
    "hunspell": ("https://raw.githubusercontent.com/Goudron/ru-spelling-dictionary/0f983e6b1950e240f5512974a5f146ea56f64bed/ru_RU.dic",
                 "1530696fd21917aa246a7ebe3f7098d5aa274364", "MPL 2.0"),
}
RU = re.compile(r"[а-яё]{4,28}\Z")
ROWS = ("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
OFFSETS = (0., .45, .9)


def blob_hash(data: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def decode_dictionary(raw: bytes) -> str:
    """Some Hunspell mirrors use a UTF-16 BOM after an ASCII word count.

    Validate raw Git blob bytes first; this only affects text decoding.
    """
    try:
        return raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        for bom in (b"\xff\xfe", b"\xfe\xff"):
            offset = raw.find(bom, 0, 32)
            if offset >= 0:
                header = raw[:offset].decode("ascii").strip()
                body = raw[offset:].decode("utf-16")
                return header + "\n" + body if header else body
        raise ValueError("Unsupported dictionary text encoding (no UTF-16 BOM)")


def verified_source(url: str, sha: str, path: Path) -> str:
    if path.exists() and blob_hash(path.read_bytes()) == sha:
        return decode_dictionary(path.read_bytes())
    for attempt in range(4):
        try:
            with urlopen(Request(url, headers={"User-Agent": "RussianKeyboardTrainer-QA"}), timeout=90) as r:
                raw = r.read()
            if blob_hash(raw) != sha:
                raise ValueError("Source blob hash mismatch: fail closed")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
            return decode_dictionary(raw)
        except (HTTPError, URLError, TimeoutError):
            if attempt == 3:
                raise
            time.sleep(3 * (attempt + 1))
    raise RuntimeError("source unavailable")


def select_words(leeds: str, hunspell: str, minimum: int = TARGET):
    primary = list(dict.fromkeys(w.strip().lower() for w in leeds.splitlines()
                                 if RU.fullmatch(w.strip().lower())))
    raw = [line.split("/", 1)[0].strip() for line in hunspell.splitlines()[1:]]
    all_valid = {w.lower() for w in raw if RU.fullmatch(w.lower())} | set(primary)
    primary_set = set(primary)
    # Lowercase common forms, not proper-name capitalization or Hunspell flags.
    extra = {w for w in raw if RU.fullmatch(w) and w == w.lower() and w not in primary_set}
    # Select from the whole dictionary, not a single alphabetical block.
    extra_order = sorted(extra, key=lambda w: (hashlib.blake2b(w.encode(), digest_size=8).digest(), w))
    chosen = (primary + extra_order)[:minimum]
    if len(chosen) != minimum or len(set(chosen)) != minimum:
        raise ValueError("Fewer than 100k unique Cyrillic forms")
    return chosen, all_valid, {"leeds_words": min(len(primary), minimum),
                                "hunspell_additions": max(0, minimum - len(primary)),
                                "known_valid_words": len(all_valid)}


def neighbor_map():
    points = {letter: (r, i + OFFSETS[r]) for r, row in enumerate(ROWS)
              for i, letter in enumerate(row)}
    points["ё"] = (-.1, 0.)
    return {c: tuple(n for _, n in sorted(
        (((r-s)**2 + (x-y)**2, other)
         for other, (s, y) in points.items()
         if c != other and (r-s)**2 + (x-y)**2 <= 1.65)))
        for c, (r, x) in points.items()}


NEIGHBORS = neighbor_map()


def variants(word: str):
    def near():
        for i, char in enumerate(word):
            for alt in NEIGHBORS.get(char, ()):
                yield word[:i] + alt + word[i+1:], "adjacent_substitution"
    def drop():
        for i in range(len(word)):
            yield word[:i] + word[i+1:], "missing_key"
    def double():
        for i, char in enumerate(word):
            yield word[:i] + char + word[i:], "double_press"
    def flip():
        for i in range(len(word) - 1):
            if word[i] != word[i+1]:
                yield word[:i] + word[i+1] + word[i] + word[i+2:], "transposition"
    def inserted():
        for i, char in enumerate(word):
            for alt in NEIGHBORS.get(char, ())[:3]:
                yield word[:i+1] + alt + word[i+1:], "neighbor_insertion"
    def twice():
        for i in range(len(word) - 1):
            for a in NEIGHBORS.get(word[i], ())[:2]:
                for b in NEIGHBORS.get(word[i+1], ())[:2]:
                    yield word[:i] + a + b + word[i+2:], "two_neighbor_errors"
    for row in itertools.zip_longest(near(), drop(), double(), flip(), inserted(), twice()):
        for case in row:
            if case is not None:
                yield case


def create_pairs(words, all_valid, path: Path, n=PER_WORD):
    path.parent.mkdir(parents=True, exist_ok=True)
    valid = all_valid | set(words)
    used = set()
    counts = collections.Counter()
    excluded = collections.Counter()
    # mtime=0 makes gzip output reproducible across runners.
    with path.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as gz:
            with io.TextIOWrapper(gz, encoding="utf-8", newline="\n") as f:
                f.write("mistyped\tintended\terror_type\n")
                for word in words:
                    wrote = 0
                    for typo, kind in variants(word):
                        if typo == word or not RU.fullmatch(typo):
                            excluded["invalid"] += 1
                        elif typo in valid:
                            excluded["also_a_real_word"] += 1
                        elif typo in used:
                            excluded["ambiguous_or_duplicate"] += 1
                        else:
                            used.add(typo)
                            f.write(f"{typo}\t{word}\t{kind}\n")
                            counts[kind] += 1
                            wrote += 1
                            if wrote == n:
                                break
                    if wrote < n:
                        raise RuntimeError(f"Not enough distinct typos for {word}: {wrote}/{n}")
    return {"wordforms": len(words), "unique_typos": len(used),
            "distinct_pairs": sum(counts.values()), "by_rule": dict(counts),
            "rejected": dict(excluded),
            "disclaimer": "SYNTHETIC character edits only; not validated real human typo corrections"}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", type=Path, default=Path("reports/lexicon"))
    p.add_argument("--cache", type=Path, default=Path(".cache/lexicon"))
    a = p.parse_args()
    leeds = verified_source(*SOURCES["leeds"][:2], a.cache / "leeds.txt")
    hunspell = verified_source(*SOURCES["hunspell"][:2], a.cache / "ru_RU.dic")
    words, valid, stats = select_words(leeds, hunspell)
    a.out.mkdir(parents=True, exist_ok=True)
    (a.out / "words-100000.txt").write_text("\n".join(words)+"\n", encoding="utf-8")
    counts = create_pairs(words, valid, a.out / "typos-1000000.tsv.gz")
    counts.update(stats)
    counts["sources"] = [{"url": url, "git_blob": sha, "license": license}
                         for url, sha, license in SOURCES.values()]
    if counts["wordforms"] != TARGET or counts["unique_typos"] != TARGET * PER_WORD:
        raise RuntimeError(f"Million-pair validation failed: {counts}")
    (a.out / "metrics.json").write_text(json.dumps(counts, ensure_ascii=False, indent=2)+"\n",
                                        encoding="utf-8")
    print(json.dumps(counts, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
