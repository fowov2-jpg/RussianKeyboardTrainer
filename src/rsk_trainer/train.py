from __future__ import annotations
import argparse, math, time
from pathlib import Path
import torch
from torch import nn
from torch.utils.data import DataLoader, Dataset
from .dataset import load_jsonl
from .io_utils import load_config, save_json, seed_all
from .metrics import group_metrics
from .model import ContextRanker
from .tokenizer import CharTokenizer

class Rows(Dataset):
    def __init__(self, rows, tok, max_chars): self.rows=rows; self.tok=tok; self.max_chars=max_chars
    def __len__(self): return len(self.rows)
    def __getitem__(self,i):
        r=self.rows[i]; ids,mask=self.tok.encode_pair(r.left_context,r.typed,r.candidate,self.max_chars)
        return torch.tensor(ids),torch.tensor(mask),torch.tensor(float(r.label)),i

def score(model, loader, rows, device):
    model.eval(); scored=[]
    with torch.inference_mode():
        for ids,mask,label,idx in loader:
            logits=model(ids.to(device),mask.to(device)).float().cpu().tolist()
            for s,j in zip(logits,idx.tolist()):
                r=rows[j]; scored.append({"group_id":r.group_id,"candidate":r.candidate,"label":r.label,
                    "is_keep_candidate":r.is_keep_candidate,"score":float(s)})
    return group_metrics(scored)

def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--config",default="configs/ranker_v0.json")
    ap.add_argument("--train",default="data/generated/train.jsonl"); ap.add_argument("--val",default="data/generated/val.jsonl")
    ap.add_argument("--out",default="artifacts/ranker_v0"); args=ap.parse_args()
    cfg=load_config(args.config); seed_all(cfg["seed"])
    out=Path(args.out); out.mkdir(parents=True,exist_ok=True)
    tok=CharTokenizer.default(); tok.save(out/"tokenizer.json")
    train_rows=load_jsonl(args.train); val_rows=load_jsonl(args.val)
    train_ds=Rows(train_rows,tok,cfg["max_chars"]); val_ds=Rows(val_rows,tok,cfg["max_chars"])
    train_loader=DataLoader(train_ds,batch_size=cfg["batch_size"],shuffle=True,num_workers=cfg["num_workers"],pin_memory=True)
    val_loader=DataLoader(val_ds,batch_size=cfg["batch_size"]*2,shuffle=False,num_workers=cfg["num_workers"])
    device=torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model=ContextRanker(len(tok.itos),cfg["max_chars"],cfg["d_model"],cfg["nhead"],cfg["num_layers"],cfg["dim_feedforward"],cfg["dropout"]).to(device)
    if cfg.get("compile") and hasattr(torch,"compile"): model=torch.compile(model)
    opt=torch.optim.AdamW(model.parameters(),lr=cfg["learning_rate"],weight_decay=cfg["weight_decay"])
    steps=max(1, math.ceil(len(train_loader)/cfg["grad_accum_steps"])*cfg["epochs"]); warm=max(1,int(steps*cfg["warmup_ratio"]))
    def lr_lambda(s):
        if s < warm: return (s+1)/warm
        return max(0.05,(steps-s)/max(1,steps-warm))
    sched=torch.optim.lr_scheduler.LambdaLR(opt,lr_lambda)
    pos_weight=torch.tensor([1.0],device=device)
    loss_fn=nn.BCEWithLogitsLoss(reduction="none",pos_weight=pos_weight)
    scaler=torch.amp.GradScaler("cuda",enabled=bool(cfg["amp"] and device.type=="cuda"))
    best=-1e9; bad=0; global_step=0; history=[]
    for epoch in range(1,cfg["epochs"]+1):
        model.train(); opt.zero_grad(set_to_none=True); total_loss=0.; started=time.time()
        for batch_i,(ids,mask,label,idx) in enumerate(train_loader,1):
            ids=ids.to(device,non_blocking=True); mask=mask.to(device,non_blocking=True); label=label.to(device)
            with torch.amp.autocast(device_type=device.type,enabled=bool(cfg["amp"] and device.type=="cuda")):
                logits=model(ids,mask); losses=loss_fn(logits,label)
                keep_flags=torch.tensor([1.0 if train_rows[j].is_keep_candidate and train_rows[j].label==1 else 0.0 for j in idx.tolist()],device=device)
                weights=1.0 + keep_flags*(cfg["keep_loss_weight"]-1.0)
                loss=(losses*weights).mean()/cfg["grad_accum_steps"]
            scaler.scale(loss).backward(); total_loss += float(loss.detach())*cfg["grad_accum_steps"]
            if batch_i % cfg["grad_accum_steps"]==0 or batch_i==len(train_loader):
                scaler.unscale_(opt); torch.nn.utils.clip_grad_norm_(model.parameters(),1.0)
                scaler.step(opt); scaler.update(); opt.zero_grad(set_to_none=True); sched.step(); global_step+=1
        metrics=score(model,val_loader,val_rows,device); metrics.update({"epoch":epoch,"train_loss":total_loss/max(1,len(train_loader)),"seconds":time.time()-started})
        history.append(metrics); print(metrics,flush=True)
        if metrics["utility"] > best:
            best=metrics["utility"]; bad=0
            raw=model._orig_mod if hasattr(model,"_orig_mod") else model
            torch.save({"state_dict":raw.state_dict(),"config":cfg,"vocab_size":len(tok.itos)},out/"best.pt")
            save_json(metrics,out/"best_metrics.json")
        else:
            bad+=1
            if bad>=cfg["patience"]: break
    save_json(history,out/"history.json")
if __name__=="__main__": main()
