from pathlib import Path
import subprocess
import sys

from rsk_trainer.build_dataset import split_sentences
from rsk_trainer.tokenizer import CharTokenizer


def test_duplicate_sentences_never_cross_data_splits():
    sentences = [f"Это тестовое предложение номер {i}" for i in range(30)]
    sentences.extend(["это   тестовое предложение номер 3", "Это тестовое предложение номер 3"])
    train, val, test = split_sentences(sentences, seed=42)
    assert len(train) + len(val) + len(test) == len(sentences)
    normalize = lambda rows: {" ".join(s.split()).casefold() for s in rows}
    assert not (normalize(train) & normalize(val))
    assert not (normalize(train) & normalize(test))
    assert not (normalize(val) & normalize(test))


def test_tokenizer_zero_context_budget_keeps_typed_and_candidate():
    tokenizer = CharTokenizer.default()
    typed, candidate = "а" * 24, "б" * 24
    ids, mask = tokenizer.encode_pair("Это очень длинный предшествующий контекст " * 4, typed, candidate, 52)
    expected = [tokenizer.cls_id, tokenizer.sep_id]
    expected.extend([tokenizer.stoi["а"]] * 24)
    expected.append(tokenizer.sep_id)
    expected.extend([tokenizer.stoi["б"]] * 24)
    expected.append(tokenizer.sep_id)
    assert ids == expected
    assert all(mask)


def test_demo_corpus_generator_creates_missing_directories(tmp_path):
    script = Path(__file__).resolve().parents[1] / "scripts" / "make_demo_corpus.py"
    subprocess.run([sys.executable, str(script)], cwd=tmp_path, check=True)
    output = tmp_path / "data" / "examples" / "demo_clean.txt"
    assert output.exists()
    assert len(output.read_text(encoding="utf-8").splitlines()) == 1200
