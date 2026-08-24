from __future__ import annotations

from collections import defaultdict


def group_metrics(rows: list[dict]) -> dict[str, float]:
    groups=defaultdict(list)
    for r in rows: groups[r["group_id"]].append(r)
    total=correct=keep_total=keep_correct=false_corrections=0
    typo_total=typo_correct=0
    for items in groups.values():
        total += 1
        pred=max(items, key=lambda x:x["score"])
        gold=next(x for x in items if x["label"] == 1)
        if pred["candidate"] == gold["candidate"]: correct += 1
        if gold["is_keep_candidate"]:
            keep_total += 1
            if pred["candidate"] == gold["candidate"]: keep_correct += 1
            else: false_corrections += 1
        else:
            typo_total += 1
            if pred["candidate"] == gold["candidate"]: typo_correct += 1
    accuracy=correct/max(total,1)
    keep_acc=keep_correct/max(keep_total,1)
    typo_recall=typo_correct/max(typo_total,1)
    fcr=false_corrections/max(keep_total,1)
    utility = accuracy - 3.0*fcr
    return {"groups": total, "accuracy": accuracy, "keep_accuracy": keep_acc,
            "false_correction_rate": fcr, "typo_recall": typo_recall, "utility": utility}
