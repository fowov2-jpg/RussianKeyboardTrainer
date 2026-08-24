from __future__ import annotations

from collections import defaultdict


def _predict(items: list[dict], keep_margin: float = 0.0) -> dict:
    keep = next((x for x in items if x.get("is_keep_candidate")), None)
    if keep is None:
        return max(items, key=lambda x: x["score"])
    non_keep = [x for x in items if not x.get("is_keep_candidate")]
    if not non_keep:
        return keep
    best_other = max(non_keep, key=lambda x: x["score"])
    return best_other if best_other["score"] > keep["score"] + keep_margin else keep


def group_metrics(rows: list[dict], keep_margin: float = 0.0) -> dict[str, float]:
    groups = defaultdict(list)
    for r in rows:
        groups[r["group_id"]].append(r)
    total = correct = keep_total = keep_correct = false_corrections = 0
    typo_total = typo_correct = 0
    for items in groups.values():
        total += 1
        pred = _predict(items, keep_margin)
        gold = next(x for x in items if x["label"] == 1)
        if pred["candidate"] == gold["candidate"]:
            correct += 1
        if gold["is_keep_candidate"]:
            keep_total += 1
            if pred["candidate"] == gold["candidate"]:
                keep_correct += 1
            else:
                false_corrections += 1
        else:
            typo_total += 1
            if pred["candidate"] == gold["candidate"]:
                typo_correct += 1
    accuracy = correct / max(total, 1)
    keep_acc = keep_correct / max(keep_total, 1)
    typo_recall = typo_correct / max(typo_total, 1)
    fcr = false_corrections / max(keep_total, 1)
    utility = accuracy - 3.0 * fcr
    return {
        "groups": total,
        "accuracy": accuracy,
        "keep_accuracy": keep_acc,
        "false_correction_rate": fcr,
        "typo_recall": typo_recall,
        "utility": utility,
        "keep_margin": float(keep_margin),
    }


def calibrate_keep_margin(rows: list[dict], max_false_correction_rate: float = 0.005) -> dict[str, float]:
    groups = defaultdict(list)
    for r in rows:
        groups[r["group_id"]].append(r)
    deltas = []
    for items in groups.values():
        keep = next((x for x in items if x.get("is_keep_candidate")), None)
        others = [x for x in items if not x.get("is_keep_candidate")]
        if keep is None or not others:
            continue
        deltas.append(max(x["score"] for x in others) - keep["score"])
    if not deltas:
        return group_metrics(rows, 0.0)

    unique = sorted(set(float(x) for x in deltas))
    if len(unique) > 300:
        step = (len(unique) - 1) / 299
        unique = [unique[round(i * step)] for i in range(300)]
    eps = 1e-6
    margins = [0.0] + [max(0.0, x + eps) for x in unique if x >= 0.0]
    margins.append(max(0.0, max(unique) + 1e-3))

    candidates = [group_metrics(rows, m) for m in sorted(set(margins))]
    feasible = [m for m in candidates if m["false_correction_rate"] <= max_false_correction_rate]
    pool = feasible if feasible else candidates
    return max(pool, key=lambda m: (m["utility"], m["typo_recall"], -m["false_correction_rate"], -m["keep_margin"]))
