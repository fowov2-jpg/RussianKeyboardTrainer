import pytest
from rsk_trainer.dataset import RankExample
from rsk_trainer.tokenizer import CharTokenizer
from rsk_trainer.train import GroupRows


def test_group_rows_requires_exactly_one_positive():
    rows = [
        RankExample("Вася", "кушачет", "кушачет", 0, "g", "test", True),
        RankExample("Вася", "кушачет", "кушает", 0, "g", "test", False),
    ]
    with pytest.raises(ValueError, match="exactly one positive"):
        GroupRows(rows, CharTokenizer.default(), 64)


def test_group_rows_requires_keep_candidate():
    rows = [
        RankExample("Вася", "кушачет", "кушает", 1, "g", "test", False),
        RankExample("Вася", "кушачет", "кушать", 0, "g", "test", False),
    ]
    with pytest.raises(ValueError, match="no KEEP candidate"):
        GroupRows(rows, CharTokenizer.default(), 64)
