from pathlib import Path
import runpy
import json

import pytest


parse = runpy.run_path(
    str(Path(__file__).resolve().parents[1] / "scripts" / "ci_parse_android_benchmark.py")
)["parse_android_benchmark"]


def example():
    return {
        "scenario": "synthetic-ranker-int8",
        "device_type": "github-actions-android-emulator",
        "model_quality_validated": False,
        "model_used_for_ime_autocorrection": False,
        "threads": 1,
        "candidate_count": 2,
        "warmup_iterations": 5,
        "measured_iterations": 40,
        "model_open_ms": 5.0,
        "inference_p50_ms": 2.0,
        "inference_p95_ms": 4.0,
        "inference_max_ms": 7.0,
        "process_pss_before_kb": 30000,
        "process_pss_with_session_kb": 40000,
        "process_pss_after_inference_kb": 42000,
        "process_pss_after_close_kb": 37000,
    }


def line(data):
    return "I/RSK_ONNX_BENCH( 2031): ONNX_BENCH_JSON=" + json.dumps(data)


def test_parse_valid_benchmark():
    assert parse(line(example()))["inference_p95_ms"] == 4.0


def test_missing_and_multiple_benchmarks_fail():
    with pytest.raises(ValueError, match="exactly one"):
        parse("no such tag")
    with pytest.raises(ValueError, match="exactly one"):
        parse(line(example()) + "\n" + line(example()))


@pytest.mark.parametrize("metric", ["model_open_ms", "inference_p50_ms", "process_pss_after_inference_kb"])
def test_rejects_negative_or_invalid_metrics(metric):
    bad = example()
    bad[metric] = -1
    with pytest.raises(ValueError, match="Invalid nonnegative metric"):
        parse(line(bad))


def test_rejects_inconsistent_percentiles():
    bad = example()
    bad["inference_p95_ms"] = 1.0
    with pytest.raises(ValueError, match="out of order"):
        parse(line(bad))


def test_rejects_wrong_provenance():
    bad = example()
    bad["model_quality_validated"] = True
    with pytest.raises(ValueError, match="provenance"):
        parse(line(bad))
