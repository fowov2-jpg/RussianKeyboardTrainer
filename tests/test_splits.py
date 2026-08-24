from rsk_trainer.build_dataset import split_sentences


def test_sentence_splits_do_not_overlap():
    rows = [f"Это тестовое предложение номер {i}" for i in range(100)]
    train, val, test = split_sentences(rows, seed=42)
    assert len(train) == 80 and len(val) == 10 and len(test) == 10
    assert not (set(train) & set(val))
    assert not (set(train) & set(test))
    assert not (set(val) & set(test))
