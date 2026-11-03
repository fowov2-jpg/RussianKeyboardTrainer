"""Bundle a synthetic ONNX ranker with reproducible Python score fixtures.

This is a wiring/inference test, not a Russian-language quality test.
Only synthetic CI model weights belong in this temporary Android build.
"""
import argparse
import json
import shutil
from pathlib import Path

import numpy as np
import onnxruntime as ort

from rsk_trainer.tokenizer import CharTokenizer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact-dir", required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()
    src = Path(args.artifact_dir)
    dst = Path(args.out_dir)
    dst.mkdir(parents=True, exist_ok=True)
    for filename in ("ranker_int8.onnx", "ranker_metadata.json", "tokenizer.json"):
        shutil.copy2(src / filename, dst / filename)

    tokenizer = CharTokenizer.load(src / "tokenizer.json")
    meta = json.loads((src / "ranker_metadata.json").read_text(encoding="utf-8"))
    context = "Я"
    typed = "севодня"
    candidates = ["севодня", "сегодня"]
    encoded = [tokenizer.encode_pair(context, typed, candidate, meta["max_chars"])
               for candidate in candidates]
    feeds = {
        "input_ids": np.array([x[0] for x in encoded], dtype=np.int64),
        "attention_mask": np.array([x[1] for x in encoded], dtype=np.int64),
    }
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    session = ort.InferenceSession(str(src / "ranker_int8.onnx"),
                                   sess_options=options, providers=["CPUExecutionProvider"])
    scores = session.run(["score"], feeds)[0]
    if scores.shape != (2,) or not np.all(np.isfinite(scores)):
        raise ValueError("Bad ONNX scores")
    gold = {
        "context": context, "typed": typed, "candidates": candidates,
        "scores": scores.astype(float).tolist(),
        "warning": "Synthetic smoke model; never enable automatic text edits"
    }
    (dst / "ranker_reference.json").write_text(
        json.dumps(gold, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Prepared synthetic model bundle: {sum(p.stat().st_size for p in dst.iterdir())} bytes")


if __name__ == "__main__":
    main()
