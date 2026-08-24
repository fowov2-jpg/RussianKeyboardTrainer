from __future__ import annotations
import argparse, math, time
from collections import defaultdict
from pathlib import Path
import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset
from .dataset import load_jsonl
from .io_utils import load_config, save_json, seed_all
from .metrics import calibrate_keep_margin, group_metrics
from .model import ContextRanker
from .tokenizer import CharTokenizer


class Rows(Dataset):
    def __init__(self, rows, tok, max_chars):
        self.rows = rows; self.tok = tok; self.max_chars = max_chars
    def __len__(self): return len(self.rows)
    def __getitem__(self, i):
        r = self.rows[i]
        ids, mask = self.tok.encode_pair(r.left_context, r.typed, r.candidate, self.max_chars)
        return torch.tensor(ids, dtype=torch.long), torch.tensor(mask, dtype=torch.long), i


class GroupRows(Dataset):
    def __init__(self, rows, tok, max_chars):
        grouped = defaultdict(list)
        for r in rows:
            grouped[r.group_id].append(r)
        self.groups = list(grouped.values()); self.tok = tok; self.max_chars = max_chars
        for g in self.groups:
            if sum(int(r.label == 1) for r in g) != 1:
                raise ValueError(f"group {g[0].group_id} must have exactly one positive candidate")
            if not any(r.is_keep_candidate for r in g):
                raise ValueError(f"group {g[0].group_id} has no KEEP candidate")
    def __len__(self): return len(self.groups)
    def __getitem__(self, i):
        g = self.groups[i]
        encoded = [self.tok.encode_pair(r.left_context, r.typed, r.candidate, self.max_chars) for r in g]
        ids = torch.tensor([x[0] for x in encoded], dtype=torch.long)
        mask = torch.tensor([x[1] for x in encoded], dtype=torch.long)
        target = next(j for j, r in enumerate(g) if r.label == 1)
        keep_gold = bool(g[target].is_keep_candidate)
        return ids, mask, target, keep_gold


def collate_groups(batch):
    ids = torch.cat([x[0] for x in batch], dim=0)
    masks = torch.cat([x[1] for x in batch], dim=0)
    lengths = torch.tensor([x[0].shape[0] for x in batch], dtype=torch.long)
    targets = torch.tensor([x[2] for x in batch], dtype=torch.long)
    keep_gold = torch.tensor([1.0 if x[3] else 0.0 for x in batch], dtype=torch.float32)
    return ids, masks, lengths, targets, keep_gold


def score_rows(model, loader, rows, device):
    model.eval(); scored = []
    with torch.inference_mode():
        for ids, mask, idx in loader:
            logits = model(ids.to(device), mask.to(device)).float().cpu().tolist()
            for s, j in zip(logits, idx.tolist()):
                r = rows[j]
                scored.append({
                    "group_id": r.group_id, "candidate": r.candidate, "label": r.label,
                    "is_keep_candidate": r.is_keep_candidate, "score": float(s)
                })
    return scored


def score(model, loader, rows, device, keep_margin: float = 0.0):
    return group_metrics(score_rows(model, loader, rows, device), keep_margin)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="configs/ranker_v0.json")
    ap.add_argument("--train", default="data/generated/train.jsonl")
    ap.add_argument("--val", default="data/generated/val.jsonl")
    ap.add_argument("--out", default="artifacts/ranker_v0")
    args = ap.parse_args()
    cfg = load_config(args.config); seed_all(cfg["seed"])
    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    tok = CharTokenizer.default(); tok.save(out / "tokenizer.json")
    train_rows = load_jsonl(args.train); val_rows = load_jsonl(args.val)
    train_ds = GroupRows(train_rows, tok, cfg["max_chars"])
    val_ds = Rows(val_rows, tok, cfg["max_chars"])
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    train_loader = DataLoader(
        train_ds, batch_size=cfg["batch_size"], shuffle=True, num_workers=cfg["num_workers"],
        pin_memory=(device.type == "cuda"), collate_fn=collate_groups
    )
    val_loader = DataLoader(val_ds, batch_size=max(256, cfg["batch_size"] * 4), shuffle=False, num_workers=cfg["num_workers"])
    model = ContextRanker(
        len(tok.itos), cfg["max_chars"], cfg["d_model"], cfg["nhead"], cfg["num_layers"],
        cfg["dim_feedforward"], cfg["dropout"]
    ).to(device)
    if cfg.get("compile") and hasattr(torch, "compile"):
        model = torch.compile(model)
    opt = torch.optim.AdamW(model.parameters(), lr=cfg["learning_rate"], weight_decay=cfg["weight_decay"])
    steps = max(1, math.ceil(len(train_loader) / cfg["grad_accum_steps"]) * cfg["epochs"])
    warm = max(1, int(steps * cfg["warmup_ratio"]))
    def lr_lambda(s):
        if s < warm: return (s + 1) / warm
        return max(0.05, (steps - s) / max(1, steps - warm))
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lr_lambda)
    scaler = torch.amp.GradScaler("cuda", enabled=bool(cfg["amp"] and device.type == "cuda"))
    best = -1e9; bad = 0; history = []
    max_fcr = float(cfg.get("max_false_correction_rate", 0.005))
    for epoch in range(1, cfg["epochs"] + 1):
        model.train(); opt.zero_grad(set_to_none=True); total_loss = 0.0; started = time.time()
        for batch_i, (ids, mask, lengths, targets, keep_gold) in enumerate(train_loader, 1):
            ids = ids.to(device, non_blocking=True); mask = mask.to(device, non_blocking=True)
            targets = targets.to(device); keep_gold = keep_gold.to(device)
            with torch.amp.autocast(device_type=device.type, enabled=bool(cfg["amp"] and device.type == "cuda")):
                logits = model(ids, mask)
                chunks = torch.split(logits, lengths.tolist())
                losses = torch.stack([
                    F.cross_entropy(chunk.unsqueeze(0), targets[i].view(1))
                    for i, chunk in enumerate(chunks)
                ])
                weights = 1.0 + keep_gold * (cfg["keep_loss_weight"] - 1.0)
                loss = (losses * weights).mean() / cfg["grad_accum_steps"]
            scaler.scale(loss).backward(); total_loss += float(loss.detach()) * cfg["grad_accum_steps"]
            if batch_i % cfg["grad_accum_steps"] == 0 or batch_i == len(train_loader):
                scaler.unscale_(opt); torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                scaler.step(opt); scaler.update(); opt.zero_grad(set_to_none=True); sched.step()
        scored = score_rows(model, val_loader, val_rows, device)
        metrics = calibrate_keep_margin(scored, max_false_correction_rate=max_fcr)
        metrics.update({
            "epoch": epoch, "train_loss": total_loss / max(1, len(train_loader)),
            "seconds": time.time() - started, "max_false_correction_rate": max_fcr,
            "train_groups": len(train_ds)
        })
        history.append(metrics); print(metrics, flush=True)
        if metrics["utility"] > best:
            best = metrics["utility"]; bad = 0
            raw = model._orig_mod if hasattr(model, "_orig_mod") else model
            torch.save({
                "state_dict": raw.state_dict(), "config": cfg, "vocab_size": len(tok.itos),
                "keep_margin": metrics["keep_margin"]
            }, out / "best.pt")
            save_json(metrics, out / "best_metrics.json")
            save_json({
                "keep_margin": metrics["keep_margin"],
                "max_false_correction_rate": max_fcr,
                "validation_false_correction_rate": metrics["false_correction_rate"],
                "validation_typo_recall": metrics["typo_recall"]
            }, out / "calibration.json")
        else:
            bad += 1
            if bad >= cfg["patience"]: break
    save_json(history, out / "history.json")

if __name__ == "__main__": main()
