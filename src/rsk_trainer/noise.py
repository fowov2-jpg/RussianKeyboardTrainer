from __future__ import annotations

import random
from dataclasses import dataclass

ROWS = ["йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"]
NEIGHBORS: dict[str, set[str]] = {}
for row_i, row in enumerate(ROWS):
    for i, ch in enumerate(row):
        s = NEIGHBORS.setdefault(ch, set())
        for j in (i - 1, i + 1):
            if 0 <= j < len(row): s.add(row[j])
        for other_i in (row_i - 1, row_i + 1):
            if 0 <= other_i < len(ROWS):
                other = ROWS[other_i]
                for j in (i - 1, i, i + 1):
                    if 0 <= j < len(other): s.add(other[j])
for ch, vals in list(NEIGHBORS.items()):
    NEIGHBORS[ch.upper()] = {v.upper() for v in vals}

@dataclass(frozen=True)
class Corruption:
    text: str
    kind: str

class RussianKeyboardNoise:
    def __init__(self, rng: random.Random): self.rng = rng

    def corrupt_word(self, word: str) -> Corruption:
        if len(word) < 2: return Corruption(word, "none")
        ops = [self._neighbor, self._delete, self._insert, self._transpose, self._duplicate]
        for _ in range(8):
            op = self.rng.choice(ops)
            out = op(word)
            if out != word and out:
                return Corruption(out, op.__name__.removeprefix("_"))
        return Corruption(word, "none")

    def _neighbor(self, word: str) -> str:
        idxs = [i for i, c in enumerate(word) if NEIGHBORS.get(c)]
        if not idxs: return word
        i = self.rng.choice(idxs)
        repl = self.rng.choice(sorted(NEIGHBORS[word[i]]))
        return word[:i] + repl + word[i+1:]

    def _delete(self, word: str) -> str:
        if len(word) <= 2: return word
        i = self.rng.randrange(len(word)); return word[:i] + word[i+1:]

    def _insert(self, word: str) -> str:
        i = self.rng.randrange(len(word)+1)
        anchor = word[min(i, len(word)-1)]
        options = sorted(NEIGHBORS.get(anchor, set("оеаи")))
        return word[:i] + self.rng.choice(options) + word[i:]

    def _transpose(self, word: str) -> str:
        if len(word) < 3: return word
        i = self.rng.randrange(len(word)-1)
        if word[i] == word[i+1]: return word
        return word[:i] + word[i+1] + word[i] + word[i+2:]

    def _duplicate(self, word: str) -> str:
        i = self.rng.randrange(len(word)); return word[:i] + word[i] + word[i:]
