"""CI-only end-to-end sanity check for ONNX exported Context Ranker.

An untrained demo checkpoint only validates export/runtime numerics, NOT the
quality of Russian autocorrection and NOT real Android latency.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from time import perf_counter

import numpy as np
import onnx
import onnxruntime as ort
import torch

from rsk_trainer.model import ContextRanker


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--quantized", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()

    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    cfg = checkpoint["config"]
    model = ContextRanker(
        checkpoint["vocab_size"], cfg["max_chars"], cfg["d_model"], cfg["nhead"],
        cfg["num_layers"], cfg["dim_feedforward"], cfg["dropout"]
    )
    model.load_state_dict(checkpoint["state_dict"])
    model.eval()

    for path in (args.model, args.quantized):
        onnx.checker.check_model(path)

    rng = np.random.default_rng(20261103)
    batch, length = 3, int(cfg["max_chars"])
    input_ids = np.zeros((batch, length), dtype=np.int64)
    attention_mask = np.zeros((batch, length), dtype=np.int64)
    input_ids[:, :18] = rng.integers(2, checkpoint["vocab_size"], (batch, 18))
    attention_mask[:, :18] = 1

    with torch.inference_mode():
        reference = model(torch.from_numpy(input_ids), torch.from_numpy(attention_mask)).numpy()
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    opts.inter_op_num_threads = 1
    scores = {}
    for label, path in (("fp32", args.model), ("int8", args.quantized)):
        session = ort.InferenceSession(path, sess_options=opts, providers=["CPUExecutionProvider"])
        feeds = {"input_ids": input_ids, "attention_mask": attention_mask}
        started = perf_counter()
        result = session.run(["score"], feeds)[0]
        wall_ms = (perf_counter() - started) * 1000
        if result.shape != (batch,) or not np.all(np.isfinite(result)):
            raise AssertionError(f"{label} invalid score shape or values: {result}")
        scores[label] = result
        # CPU runner measurement is functional smoke only; not mobile performance.
        print(f"{label} batch={batch} sanity runtime {wall_ms:.2f}ms")

    fp_error = float(np.max(np.abs(scores["fp32"] - reference)))
    int8_error = float(np.max(np.abs(scores["int8"] - scores["fp32"])))
    assert fp_error <= 0.002, f"FP32/torch mismatch: {fp_error}"
    assert int8_error <= 0.35, f"INT8/FP32 mismatch: {int8_error}"

    result = {
        "status": "pass",
        "dataset": "synthetic checkpoint / random tokens; not a quality benchmark",
        "backend": "GitHub-hosted CPU (not Android)",
        "batch_size": batch,
        "fp32_max_abs_difference_vs_torch": fp_error,
        "int8_max_abs_difference_vs_fp32": int8_error,
        "fp32_bytes": Path(args.model).stat().st_size,
        "int8_bytes": Path(args.quantized).stat().st_size,
    }
    report = Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
