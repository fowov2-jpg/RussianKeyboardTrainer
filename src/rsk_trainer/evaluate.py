from __future__ import annotations
import argparse, json
import torch
from torch.utils.data import DataLoader
from .dataset import load_jsonl
from .model import ContextRanker
from .tokenizer import CharTokenizer
from .train import Rows, score


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--data", required=True)
    ap.add_argument("--tokenizer", required=True)
    ap.add_argument("--keep-margin", type=float, default=None)
    args = ap.parse_args()
    ck = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    cfg = ck["config"]; tok = CharTokenizer.load(args.tokenizer)
    model = ContextRanker(ck["vocab_size"], cfg["max_chars"], cfg["d_model"], cfg["nhead"], cfg["num_layers"], cfg["dim_feedforward"], cfg["dropout"])
    model.load_state_dict(ck["state_dict"])
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu"); model.to(device)
    rows = load_jsonl(args.data)
    loader = DataLoader(Rows(rows, tok, cfg["max_chars"]), batch_size=512, shuffle=False)
    margin = float(ck.get("keep_margin", 0.0) if args.keep_margin is None else args.keep_margin)
    print(json.dumps(score(model, loader, rows, device, keep_margin=margin), ensure_ascii=False, indent=2))

if __name__ == "__main__": main()
