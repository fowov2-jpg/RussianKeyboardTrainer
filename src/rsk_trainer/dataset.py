from __future__ import annotations

import json
import random
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from .distance import damerau_levenshtein
from .noise import RussianKeyboardNoise

WORD_RE = re.compile(r"[А-Яа-яЁё-]{2,}")

@dataclass(frozen=True)
class RankExample:
    left_context: str
    typed: str
    candidate: str
    label: int
    group_id: str
    kind: str
    is_keep_candidate: bool


def iter_clean_sentences(path: str | Path) -> Iterable[str]:
    with Path(path).open(encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if len(s) >= 6 and WORD_RE.search(s): yield s


def _word_spans(text: str): return list(WORD_RE.finditer(text))


def generate_from_clean(sentences: Iterable[str], seed: int = 1, max_groups: int | None = None) -> list[RankExample]:
    rng = random.Random(seed); noise = RussianKeyboardNoise(rng); out = []
    group_count = 0
    reservoir: list[str] = []
    for sent_idx, sent in enumerate(sentences):
        spans = _word_spans(sent)
        if not spans: continue
        target = rng.choice(spans)
        clean = target.group(0)
        if len(clean) < 3: continue
        left = sent[:target.start()].rstrip()
        corruption = noise.corrupt_word(clean)
        typed = corruption.text
        if typed == clean: continue
        group_id = f"syn-{sent_idx}"
        candidates = [clean, typed]
        distractors = [w for w in reservoir[-300:] if w.lower() not in {clean.lower(), typed.lower()} and abs(len(w)-len(clean)) <= 2]
        rng.shuffle(distractors)
        candidates.extend(distractors[:3])
        seen=set(); candidates=[c for c in candidates if not (c.lower() in seen or seen.add(c.lower()))]
        for c in candidates:
            out.append(RankExample(left, typed, c, int(c == clean), group_id, corruption.kind, c == typed))
        reservoir.extend(m.group(0) for m in spans)
        group_count += 1
        if max_groups and group_count >= max_groups: break
    return out


def read_real_pairs_jsonl(path: str | Path) -> list[RankExample]:
    out=[]
    with Path(path).open(encoding="utf-8") as f:
        for idx, line in enumerate(f):
            if not line.strip(): continue
            row=json.loads(line)
            left=row.get("left_context", "")
            typed=row["typed"]; correct=row["correct"]
            group=row.get("id", f"real-{idx}")
            candidates=[correct, typed] + list(row.get("distractors", []))
            seen=set(); candidates=[c for c in candidates if not (c.lower() in seen or seen.add(c.lower()))]
            for c in candidates:
                out.append(RankExample(left, typed, c, int(c == correct), group, "real", c == typed))
    return out


def add_keep_groups(sentences: Iterable[str], seed: int = 2, max_groups: int | None = None) -> list[RankExample]:
    rng=random.Random(seed); out=[]; pool=[]; count=0
    for sent_idx, sent in enumerate(sentences):
        spans=_word_spans(sent)
        if not spans: continue
        target=rng.choice(spans); typed=target.group(0); left=sent[:target.start()].rstrip()
        group=f"keep-{sent_idx}"
        candidates=[typed]
        near=[w for w in pool[-500:] if w.lower()!=typed.lower() and abs(len(w)-len(typed))<=2 and damerau_levenshtein(w.lower(), typed.lower()) <= 2]
        rng.shuffle(near); candidates.extend(near[:4])
        seen=set(); candidates=[c for c in candidates if not (c.lower() in seen or seen.add(c.lower()))]
        for c in candidates:
            out.append(RankExample(left, typed, c, int(c == typed), group, "keep", c == typed))
        pool.extend(m.group(0) for m in spans); count += 1
        if max_groups and count >= max_groups: break
    return out


def save_jsonl(rows: list[RankExample], path: str | Path) -> None:
    p=Path(path); p.parent.mkdir(parents=True, exist_ok=True)
    with p.open("w", encoding="utf-8") as f:
        for r in rows: f.write(json.dumps(r.__dict__, ensure_ascii=False)+"\n")


def load_jsonl(path: str | Path) -> list[RankExample]:
    out=[]
    with Path(path).open(encoding="utf-8") as f:
        for line in f:
            if line.strip(): out.append(RankExample(**json.loads(line)))
    return out
