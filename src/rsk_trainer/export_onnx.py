from __future__ import annotations
import argparse, json
from pathlib import Path
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic
from .model import ContextRanker


class ExportWrapper(torch.nn.Module):
    def __init__(self, model): super().__init__(); self.model = model
    def forward(self, input_ids, attention_mask): return self.model(input_ids, attention_mask)


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--checkpoint", required=True); ap.add_argument("--out", default="artifacts/ranker_v0/ranker.onnx"); args = ap.parse_args()
    ck = torch.load(args.checkpoint, map_location="cpu", weights_only=False); cfg = ck["config"]
    model = ContextRanker(ck["vocab_size"], cfg["max_chars"], cfg["d_model"], cfg["nhead"], cfg["num_layers"], cfg["dim_feedforward"], cfg["dropout"])
    model.load_state_dict(ck["state_dict"]); model.eval(); wrap = ExportWrapper(model)
    out = Path(args.out); out.parent.mkdir(parents=True, exist_ok=True)
    ids = torch.zeros((1, cfg["max_chars"]), dtype=torch.long); mask = torch.ones_like(ids)
    torch.onnx.export(wrap, (ids, mask), out, input_names=["input_ids", "attention_mask"], output_names=["score"], opset_version=18,
        dynamic_axes={"input_ids": {0: "batch"}, "attention_mask": {0: "batch"}, "score": {0: "batch"}}, dynamo=False)
    q = out.with_name(out.stem + "_int8.onnx"); quantize_dynamic(str(out), str(q), weight_type=QuantType.QInt8)
    meta = out.with_name(out.stem + "_metadata.json")
    meta.write_text(json.dumps({
        "keep_margin": float(ck.get("keep_margin", 0.0)),
        "max_chars": int(cfg["max_chars"]),
        "vocab_size": int(ck["vocab_size"]),
        "score_rule": "apply non-KEEP only when best_non_keep_score > keep_score + keep_margin"
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"fp32": str(out), "fp32_bytes": out.stat().st_size, "int8": str(q), "int8_bytes": q.stat().st_size, "metadata": str(meta)}, indent=2))

if __name__ == "__main__": main()
