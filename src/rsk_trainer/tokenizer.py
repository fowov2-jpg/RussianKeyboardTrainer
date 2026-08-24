from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

SPECIAL = ["<PAD>", "<UNK>", "<CLS>", "<SEP>"]
BASE_CHARS = list(
    " абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    "0123456789.,!?;:-—()[]{}\"'«»/@#%+_=\\\n\t"
)

@dataclass
class CharTokenizer:
    stoi: dict[str, int]
    itos: list[str]

    @classmethod
    def default(cls) -> "CharTokenizer":
        chars = []
        seen = set()
        for ch in SPECIAL + BASE_CHARS:
            if ch not in seen:
                chars.append(ch); seen.add(ch)
        return cls({c: i for i, c in enumerate(chars)}, chars)

    @property
    def pad_id(self) -> int: return self.stoi["<PAD>"]
    @property
    def cls_id(self) -> int: return self.stoi["<CLS>"]
    @property
    def sep_id(self) -> int: return self.stoi["<SEP>"]
    @property
    def unk_id(self) -> int: return self.stoi["<UNK>"]

    def encode_pair(self, left_context: str, typed: str, candidate: str, max_chars: int) -> tuple[list[int], list[int]]:
        budget = max_chars - 4
        typed = typed[:24]
        candidate = candidate[:24]
        context_budget = max(0, budget - len(typed) - len(candidate))
        left_context = left_context[-context_budget:]
        seq = [self.cls_id]
        for part in (left_context, typed, candidate):
            seq.extend(self.stoi.get(ch, self.unk_id) for ch in part)
            seq.append(self.sep_id)
        seq = seq[:max_chars]
        mask = [1] * len(seq)
        if len(seq) < max_chars:
            n = max_chars - len(seq)
            seq.extend([self.pad_id] * n); mask.extend([0] * n)
        return seq, mask

    def save(self, path: str | Path) -> None:
        Path(path).write_text(json.dumps({"itos": self.itos}, ensure_ascii=False, indent=2), encoding="utf-8")

    @classmethod
    def load(cls, path: str | Path) -> "CharTokenizer":
        itos = json.loads(Path(path).read_text(encoding="utf-8"))["itos"]
        return cls({c: i for i, c in enumerate(itos)}, itos)
