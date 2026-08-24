from rsk_trainer.metrics import calibrate_keep_margin, group_metrics


def test_false_correction_is_penalized():
    rows = [
      {"group_id": "a", "candidate": "нужный", "label": 1, "is_keep_candidate": True, "score": 0.1},
      {"group_id": "a", "candidate": "нежный", "label": 0, "is_keep_candidate": False, "score": 0.9},
    ]
    m = group_metrics(rows)
    assert m["false_correction_rate"] == 1.0 and m["utility"] < 0


def test_keep_margin_blocks_weak_false_correction():
    rows = [
      {"group_id": "a", "candidate": "решением", "label": 1, "is_keep_candidate": True, "score": 1.00},
      {"group_id": "a", "candidate": "решение", "label": 0, "is_keep_candidate": False, "score": 1.03},
    ]
    assert group_metrics(rows, keep_margin=0.05)["false_correction_rate"] == 0.0


def test_calibration_prefers_safe_margin_under_fcr_cap():
    rows = [
      {"group_id": "keep", "candidate": "заказа", "label": 1, "is_keep_candidate": True, "score": 1.0},
      {"group_id": "keep", "candidate": "заказ", "label": 0, "is_keep_candidate": False, "score": 1.02},
      {"group_id": "typo", "candidate": "привильно", "label": 0, "is_keep_candidate": True, "score": 0.7},
      {"group_id": "typo", "candidate": "правильно", "label": 1, "is_keep_candidate": False, "score": 1.2},
    ]
    m = calibrate_keep_margin(rows, max_false_correction_rate=0.0)
    assert m["false_correction_rate"] == 0.0
    assert m["typo_recall"] == 1.0
    assert m["keep_margin"] > 0.02
