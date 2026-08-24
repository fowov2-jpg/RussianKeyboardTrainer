from rsk_trainer.metrics import group_metrics

def test_false_correction_is_penalized():
    rows=[
      {"group_id":"a","candidate":"нужный","label":1,"is_keep_candidate":True,"score":0.1},
      {"group_id":"a","candidate":"нежный","label":0,"is_keep_candidate":False,"score":0.9},
    ]
    m=group_metrics(rows)
    assert m["false_correction_rate"]==1.0 and m["utility"] < 0
