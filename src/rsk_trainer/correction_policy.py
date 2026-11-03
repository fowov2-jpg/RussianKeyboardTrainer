"""Reference safety policy for automatic keyboard word replacements.

Android must enforce the same checks against its live InputConnection snapshot.
"""

from __future__ import annotations

from dataclasses import dataclass
from math import isfinite
from typing import Sequence


@dataclass(frozen=True)
class CandidateScore:
    text: str
    score: float
    is_keep: bool = False
    kind: str = "spelling"  # "grammar" requires an explicit opt-in decision.


def choose_replacement(
    typed: str,
    candidates: Sequence[CandidateScore],
    *,
    keep_margin: float,
    request_revision: int,
    current_revision: int,
    sensitive_field: bool = False,
    allow_grammar: bool = False,
) -> str:
    """Return a safe correction or the original word (KEEP).

    A background result can only be used for the same input revision. The caller
    must also verify the actual text range and cursor before replacing it.
    """
    if not isfinite(keep_margin) or keep_margin < 0:
        raise ValueError("keep_margin must be finite and non-negative")
    if sensitive_field or request_revision != current_revision or not typed:
        return typed

    keep = next((c for c in candidates if c.is_keep and c.text == typed and isfinite(c.score)), None)
    if keep is None:
        return typed  # Never replace text without a trustworthy KEEP baseline.

    eligible = (
        c for c in candidates
        if not c.is_keep and c.text and c.text != typed and isfinite(c.score)
        and (c.kind == "spelling" or (allow_grammar and c.kind == "grammar"))
    )
    best = max(eligible, key=lambda c: c.score, default=None)
    if best is not None and best.score > keep.score + keep_margin:
        return best.text
    return typed
