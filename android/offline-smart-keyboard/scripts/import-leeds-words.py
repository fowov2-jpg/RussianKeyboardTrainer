#!/usr/bin/env python3
"""Offline developer-side importer of the *downloaded* CC-BY 2.5 Leeds list.

Download manually or on development computer from:
https://github.com/hingston/russian/blob/master/50000-russian-words-cyrillic-only.txt
Read LICENSE.md in that repository (CC BY 2.5).

Usage: python scripts/import-leeds-words.py downloaded-50000.txt --limit 15000
Writes app/src/main/assets/ru_words.txt, merging in existing curated forms.
No network access is used by this script or the keyboard application.
"""
from pathlib import Path
import argparse
import re

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("source", type=Path)
p.add_argument("--limit", type=int, default=15000)
p.add_argument("--output", type=Path, default=Path("app/src/main/assets/ru_words.txt"))
a = p.parse_args()
if not 1000 <= a.limit <= 50000:
    p.error("--limit must be in [1000, 50000]")
source_words = a.source.read_text(encoding="utf-8-sig").splitlines()
rows: dict[str,int] = {}
for rank, token in enumerate(source_words, 1):
    if rank > a.limit: break
    word = token.strip().lower()
    if re.fullmatch("[а-яё]{1,24}", word):
        # Ranked estimates only; NOT actual word counts.
        freq = max(100, round(100000 / (rank ** 0.5)))
        rows[word] = max(rows.get(word, 0), freq)
# Add existing hand-curated forms, including typo-review targets.
if a.output.exists():
    for row in a.output.read_text(encoding="utf-8").splitlines():
        parts = row.split()
        if len(parts) != 2 or not parts[1].isdigit(): continue
        rows[parts[0]] = max(rows.get(parts[0],0), int(parts[1]))
header = (
  "# Frequency rank source: University of Leeds Russian Internet Corpus; editing by hingston.\n"
  "# CC BY 2.5: https://github.com/hingston/russian/blob/master/LICENSE.md\n"
  "# Rank-based weights are approximations; curated additional forms have guessed weights.\n"
)
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(header + ''.join(f"{word} {freq}\n" for word, freq in rows.items()), encoding="utf-8")
print(f"Wrote {len(rows)} unique words to {a.output}; remember attribution/license")
