"""Build a word-level diagnostic set from the existing SAGE sentence testset.

This does NOT replace sentence-level evaluation. It only extracts simple 1:1 word
substitutions (e.g. 'правельно' -> 'правильно') to test the Context Ranker slot.
Output matches RankExample JSONL and must never be merged into train.
"""
from __future__ import annotations
import argparse, difflib, json, re
from pathlib import Path
WORD = re.compile(r"[А-Яа-яЁё-]+")

def unesc(s:str)->str:
    return s.replace('\\t','\t').replace('\\n','\n').replace('\\\\','\\')

def read_cases(path:Path):
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line or line.startswith('#'): continue
        parts=line.split('\t')
        if len(parts)>=4 and parts[0].isdigit():
            yield parts[0],parts[1],unesc(parts[2]),unesc(parts[3])

def words_with_spans(s): return [(m.group(0),m.start(),m.end()) for m in WORD.finditer(s)]

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--input',required=True); ap.add_argument('--out',required=True); args=ap.parse_args()
    rows=[]; groups=0
    for cid,cat,src,exp in read_cases(Path(args.input)):
        a=words_with_spans(src); b=words_with_spans(exp)
        aw=[x[0].lower() for x in a]; bw=[x[0].lower() for x in b]
        sm=difflib.SequenceMatcher(a=aw,b=bw,autojunk=False)
        subn=0
        for tag,i1,i2,j1,j2 in sm.get_opcodes():
            if tag=='replace' and i2-i1==1 and j2-j1==1:
                typed=a[i1][0]; correct=b[j1][0]
                if typed.lower()==correct.lower(): continue
                left=src[:a[i1][1]].rstrip()[-64:]
                gid=f'sage-{cid}-{subn}'; subn+=1; groups+=1
                for cand,label,keep in [(correct,1,False),(typed,0,True)]:
                    rows.append({'left_context':left,'typed':typed,'candidate':cand,'label':label,'group_id':gid,'kind':f'external:{cat}','is_keep_candidate':keep})
    out=Path(args.out); out.parent.mkdir(parents=True,exist_ok=True)
    with out.open('w',encoding='utf-8') as f:
        for r in rows: f.write(json.dumps(r,ensure_ascii=False)+'\n')
    print(f'extracted groups={groups}, rows={len(rows)}')
if __name__=='__main__': main()
