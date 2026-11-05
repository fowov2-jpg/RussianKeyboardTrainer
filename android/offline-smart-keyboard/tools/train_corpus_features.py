#!/usr/bin/env python3
"""Offline non-neural training: build Russian word frequency/context tables.

Input: raw CC-BY-4.0 GEC CSV and MIT RLC CSV, downloaded separately.
Output: only aggregated, de-identified wordforms, bigrams, orthographic rules
and held-out token pairs. Do not commit source learner sentences.

This reproducibility tool is NOT executed on the Android device.
"""
import argparse
import collections
import csv
from pathlib import Path
import re

TOKEN = re.compile(r"[а-яё]+")
WORD = re.compile(r"^[а-яё]{3,22}$")
PAIR_WORD = re.compile(r"^[а-яё]{4,22}$")

def fnv_hash(s):
    # Matches JavaScript charCodeAt / UTF-16 FNV-1a used for v0.16 split.
    h = 2166136261
    encoded = s.encode("utf-16-le", errors="surrogatepass")
    for i in range(0, len(encoded), 2):
        code = encoded[i] | (encoded[i + 1] << 8)
        h = ((h ^ code) * 16777619) & 0xffffffff
    return h

def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        curr = [i]
        for j, y in enumerate(b, 1):
            curr.append(min(prev[j] + 1, curr[-1] + 1, prev[j-1] + (x != y)))
        prev = curr
    return prev[-1]

def known_words(assets):
    names = ("ru_words.txt", "ru_words_leeds_10k.txt")
    known = set()
    for name in names:
        for line in (assets / name).read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.startswith("#"):
                known.add(line.split()[0].lower())
    return known

def make_gec(source, assets, tests, known):
    counts, adjacent = collections.Counter(), collections.Counter()
    held, seen = set(), set()
    raw_rows = train = test_count = 0
    with source.open(encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            raw_rows += 1
            original = row["incorrect"] or ""
            corrected = row["correct"] or ""
            if not corrected or corrected in seen:
                continue
            seen.add(corrected)
            tokens = TOKEN.findall(corrected.lower())
            if fnv_hash(corrected) % 10 >= 8:
                test_count += 1
                wrong = TOKEN.findall(original.lower())
                if len(wrong) == len(tokens):
                    changes = [(a, b) for a, b in zip(wrong, tokens) if a != b]
                    if len(changes) == 1:
                        a, b = changes[0]
                        if PAIR_WORD.fullmatch(a) and PAIR_WORD.fullmatch(b) and edit_distance(a, b) <= 2 and a not in known:
                            held.add((a, b))
                continue
            train += 1
            counts.update(w for w in tokens if WORD.fullmatch(w))
            adjacent.update((a, b) for a, b in zip(tokens, tokens[1:])
                            if re.fullmatch(r"[а-яё]{2,22}", a) and
                            re.fullmatch(r"[а-яё]{2,22}", b))
    vocab = sorted(((w, n) for w, n in counts.items() if n >= 4 and w not in known),
                   key=lambda x: (-x[1], x[0]))[:1700]
    enriched = known | {w for w, _ in vocab}
    bigrams = sorted(((a, b, n) for (a, b), n in adjacent.items()
                      if n >= 5 and a in enriched and b in enriched),
                     key=lambda x: (-x[2], x[0], x[1]))[:1200]
    text = "# GEC-derived word forms (CC BY 4.0); TRAIN split only.\n" + "\n".join(
        f"{w} {min(7000, 850 + n*12)}" for w, n in vocab) + "\n"
    (assets / "ru_gec_wordforms.txt").write_text(text, encoding="utf-8")
    text = "# GEC-derived bigram counts (CC BY 4.0); capped to 80.\n" + "\n".join(
        f"{a} {b} {min(80, n)}" for a, b, n in bigrams) + "\n"
    (assets / "ru_gec_bigrams.txt").write_text(text, encoding="utf-8")
    (tests / "gec_holdout.tsv").write_text(
        "# Held-out GEC token-level examples; not all are keyboard typos.\n" +
        "\n".join("\t".join(x) for x in sorted(held)[:200]) + "\n",
        encoding="utf-8")
    print(f"GEC: raw={raw_rows}, unique={len(seen)}, train={train}, "
          f"heldout={test_count}, new_wordforms={len(vocab)}, bigrams={len(bigrams)}")
    return enriched

def make_rlc(train_source, test_source, assets, tests, known):
    choices = collections.defaultdict(set)
    with train_source.open(encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            tags = {x.strip().lower() for x in (row["tag"] or "").split(",")}
            if row.get("annotation_source") != "manual" or not tags.intersection({"ortho", "misspell"}):
                continue
            source = row["quote"].strip().lower()
            target = row["correction"].strip().lower()
            if source == target or not (PAIR_WORD.fullmatch(source) and PAIR_WORD.fullmatch(target)):
                continue
            if source in known or target not in known or edit_distance(source, target) > 2:
                continue
            choices[(source, target)].add(row["sentence_id"])
    rules = sorted(((s, t, len(ids)) for (s, t), ids in choices.items() if len(ids) >= 2),
                   key=lambda x: (-x[2], x[0], x[1]))[:200]
    (assets / "ru_rlc_repeated_typos.txt").write_text(
        "# MIT RLC-GEC repeated manual orthographic annotations. Not unconditional fixes.\n" +
        "\n".join(f"{s} {t} {n}" for s, t, n in rules) + "\n",
        encoding="utf-8")

    withheld = {}
    with test_source.open(encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            tag = (row["tag"] or "").strip().lower()
            if not ("ortho" in tag or "misspell" in tag):
                continue
            source = row["quote"].strip().lower()
            target = row["correction"].strip().lower()
            if (source != target and WORD.fullmatch(source) and WORD.fullmatch(target)
                    and edit_distance(source, target) <= 3):
                withheld.setdefault((source, target), tag)
    (tests / "rlc_test_holdout.tsv").write_text(
        "# MIT RLC-Test. Independent data; only word pairs, not learner sentences.\n" +
        "\n".join(f"{a}\t{b}\t{tag}" for (a, b), tag in sorted(withheld.items())) + "\n",
        encoding="utf-8")
    print(f"RLC: training_rules={len(rules)}, independent_test_pairs={len(withheld)}")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", type=Path, default=Path("data/raw"))
    parser.add_argument("--assets", type=Path, default=Path("app/src/main/assets"))
    parser.add_argument("--test-assets", type=Path, default=Path("app/src/test/assets"))
    args = parser.parse_args()
    args.assets.mkdir(parents=True, exist_ok=True)
    args.test_assets.mkdir(parents=True, exist_ok=True)
    known = known_words(args.assets)
    new_known = make_gec(args.raw / "russian_gec.csv", args.assets, args.test_assets, known)
    make_rlc(args.raw / "rlc_annotations.csv", args.raw / "rlc_test.csv",
             args.assets, args.test_assets, new_known)
    print("Only aggregate training features and independent evaluation tokens are saved.")

if __name__ == "__main__":
    main()
