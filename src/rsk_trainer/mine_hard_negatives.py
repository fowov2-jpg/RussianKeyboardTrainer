from __future__ import annotations
import argparse, json
from pathlib import Path
import torch
from torch.utils.data import DataLoader
from .dataset import load_jsonl
from .model import ContextRanker
from .tokenizer import CharTokenizer
from .train import Rows

def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--checkpoint",required=True); ap.add_argument("--data",required=True); ap.add_argument("--tokenizer",required=True); ap.add_argument("--out",default="data/generated/hard_negatives.jsonl"); args=ap.parse_args()
    ck=torch.load(args.checkpoint,map_location="cpu",weights_only=False); cfg=ck["config"]; tok=CharTokenizer.load(args.tokenizer)
    model=ContextRanker(ck["vocab_size"],cfg["max_chars"],cfg["d_model"],cfg["nhead"],cfg["num_layers"],cfg["dim_feedforward"],cfg["dropout"]); model.load_state_dict(ck["state_dict"])
    device=torch.device("cuda" if torch.cuda.is_available() else "cpu"); model.to(device).eval(); rows=load_jsonl(args.data)
    loader=DataLoader(Rows(rows,tok,cfg["max_chars"]),batch_size=512,shuffle=False); scores={}
    with torch.inference_mode():
        for ids,mask,label,idx in loader:
            vals=model(ids.to(device),mask.to(device)).cpu().tolist()
            for v,j in zip(vals,idx.tolist()): scores[j]=float(v)
    groups={}
    for i,r in enumerate(rows): groups.setdefault(r.group_id,[]).append((i,r))
    out=Path(args.out); out.parent.mkdir(parents=True,exist_ok=True); count=0
    with out.open("w",encoding="utf-8") as f:
        for items in groups.values():
            pred=max(items,key=lambda ir:scores[ir[0]])[1]; gold=next(r for _,r in items if r.label==1)
            if pred.candidate != gold.candidate:
                f.write(json.dumps({"group_id":gold.group_id,"left_context":gold.left_context,"typed":gold.typed,"correct":gold.candidate,"wrong_prediction":pred.candidate,"kind":gold.kind},ensure_ascii=False)+"\n"); count+=1
    print(f"hard negatives: {count}")
if __name__=="__main__": main()
