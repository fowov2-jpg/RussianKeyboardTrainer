"""Extract and validate synthetic Android ONNX benchmark JSON from logcat.

Only aggregated numeric metrics are ever logged, never keyboard input.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

PREFIX = "ONNX_BENCH_JSON="
NONNEGATIVE = [
    "model_open_ms", "inference_p50_ms", "inference_p95_ms",
    "inference_max_ms", "process_pss_before_kb",
    "process_pss_with_session_kb", "process_pss_after_inference_kb",
    "process_pss_after_close_kb",
]


def parse_android_benchmark(log: str) -> dict:
    matches = [line.split(PREFIX, 1)[1].strip()
               for line in log.splitlines() if PREFIX in line]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one ONNX benchmark in logcat, found {len(matches)}")
    data = json.loads(matches[0])
    if not isinstance(data, dict) or data.get("scenario") != "synthetic-ranker-int8":
        raise ValueError("Wrong benchmark scenario")
    if data.get("device_type") != "github-actions-android-emulator":
        raise ValueError("Wrong benchmark device")
    if data.get("model_quality_validated") is not False or data.get("model_used_for_ime_autocorrection") is not False:
        raise ValueError("Unsafe benchmark provenance")
    if data.get("threads") != 1 or data.get("measured_iterations") != 40:
        raise ValueError("Unexpected benchmark configuration")
    if not isinstance(data.get("candidate_count"), int) or data["candidate_count"] < 2:
        raise ValueError("Invalid candidate count")
    for key in NONNEGATIVE:
        value = data.get(key)
        if not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value) or value < 0:
            raise ValueError(f"Invalid nonnegative metric {key}: {value}")
    if not (data["inference_p50_ms"] <= data["inference_p95_ms"] <= data["inference_max_ms"]):
        raise ValueError("Latency percentiles out of order")
    return data


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    metrics = parse_android_benchmark(args.log.read_text(encoding="utf-8"))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(metrics, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("Validated Android synthetic ONNX benchmark, latency and process PSS reported")


if __name__ == "__main__":
    main()
