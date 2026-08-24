from __future__ import annotations
import argparse, random
from pathlib import Path
from .dataset import iter_clean_sentences, generate_from_clean, add_keep_groups, read_real_pairs_jsonl, save_jsonl

def split_by_group(rows, seed=1):
    groups={}
    for r in rows: groups.setdefault(r.group_id, []).append(r)
    ids=list(groups); random.Random(seed).shuffle(ids)
    n=len(ids); a=int(n*.8); b=int(n*.9)
    def collect(sel): return [r for gid in sel for r in groups[gid]]
    return collect(ids[:a]), collect(ids[a:b]), collect(ids[b:])

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--clean", required=True, help="UTF-8: one clean Russian sentence per line")
    ap.add_argument("--out", default="data/generated")
    ap.add_argument("--synthetic-groups", type=int, default=50000)
    ap.add_argument("--keep-groups", type=int, default=40000)
    ap.add_argument("--real-jsonl")
    ap.add_argument("--seed", type=int, default=20260824)
    args=ap.parse_args()
    sentences=list(iter_clean_sentences(args.clean))
    syn=generate_from_clean(sentences, args.seed, args.synthetic_groups)
    keep=add_keep_groups(sentences, args.seed+1, args.keep_groups)
    real=read_real_pairs_jsonl(args.real_jsonl) if args.real_jsonl else []
    train,val,test=split_by_group(syn+keep+real,args.seed)
    out=Path(args.out); out.mkdir(parents=True,exist_ok=True)
    save_jsonl(train,out/"train.jsonl"); save_jsonl(val,out/"val.jsonl"); save_jsonl(test,out/"test.jsonl")
    print(f"groups/rows -> train={len(train)} val={len(val)} test={len(test)}")
if __name__ == "__main__": main()
