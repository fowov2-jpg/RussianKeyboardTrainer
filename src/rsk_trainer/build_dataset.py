from __future__ import annotations
import argparse, random
from pathlib import Path
from .dataset import iter_clean_sentences, generate_from_clean, add_keep_groups, read_real_pairs_jsonl, save_jsonl


def split_sentences(sentences: list[str], seed: int = 1):
    items = list(sentences)
    random.Random(seed).shuffle(items)
    n = len(items); a = int(n * .8); b = int(n * .9)
    return items[:a], items[a:b], items[b:]


def split_by_group(rows, seed=1):
    groups = {}
    for r in rows:
        groups.setdefault(r.group_id, []).append(r)
    ids = list(groups); random.Random(seed).shuffle(ids)
    n = len(ids); a = int(n * .8); b = int(n * .9)
    def collect(sel): return [r for gid in sel for r in groups[gid]]
    return collect(ids[:a]), collect(ids[a:b]), collect(ids[b:])


def _share(total: int, frac: float) -> int:
    if total <= 0: return 0
    return max(1, int(round(total * frac)))


def _group_count(rows) -> int:
    return len({r.group_id for r in rows})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clean", required=True, help="UTF-8: one clean Russian sentence per line")
    ap.add_argument("--out", default="data/generated")
    ap.add_argument("--synthetic-groups", type=int, default=50000)
    ap.add_argument("--keep-groups", type=int, default=40000)
    ap.add_argument("--real-jsonl")
    ap.add_argument("--seed", type=int, default=20260824)
    args = ap.parse_args()

    sentences = list(iter_clean_sentences(args.clean))
    train_s, val_s, test_s = split_sentences(sentences, args.seed)
    splits = [("train", train_s, .8, 0), ("val", val_s, .1, 1000), ("test", test_s, .1, 2000)]
    built = {}
    for name, sents, frac, seed_off in splits:
        syn = generate_from_clean(sents, args.seed + seed_off, _share(args.synthetic_groups, frac))
        keep = add_keep_groups(sents, args.seed + 1 + seed_off, _share(args.keep_groups, frac))
        built[name] = syn + keep

    if args.real_jsonl:
        real = read_real_pairs_jsonl(args.real_jsonl)
        real_train, real_val, real_test = split_by_group(real, args.seed + 77)
        built["train"].extend(real_train); built["val"].extend(real_val); built["test"].extend(real_test)

    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    for name in ("train", "val", "test"):
        save_jsonl(built[name], out / f"{name}.jsonl")
        print(f"{name}: groups={_group_count(built[name])} rows={len(built[name])}")

if __name__ == "__main__": main()
