# RussianKeyboardTrainer 1.0.0 CoGo

Trainer для первой собственной малой модели Russian Smart Keyboard 1.8.x — Context Ranker.

## Что обучаем

Не генеративную LM. Модель получает:

`[левый контекст] + [набранное слово] + [кандидат]`

и выдаёт один score. Кандидаты ранжируются внутри группы. Один из кандидатов всегда должен быть KEEP (исходное набранное слово).

Архитектура v0: char-level TransformerEncoder, 3 слоя, d_model=128, max_chars=96. Она специально маленькая и не требует внешнего pretrained checkpoint.

## Быстрый старт

```bash
python -m venv .venv
source .venv/bin/activate  # Windows: .venv\\Scripts\\activate
pip install -e '.[dev]'
pytest -q
python scripts/make_demo_corpus.py
rsk-build --clean data/examples/demo_clean.txt --out data/generated --synthetic-groups 300 --keep-groups 300
rsk-train --config configs/ranker_v0.json --out artifacts/ranker_v0
rsk-export --checkpoint artifacts/ranker_v0/best.pt --out artifacts/ranker_v0/ranker.onnx
```

Для реального обучения положить чистый русский корпус (одно предложение на строку) локально в `data/raw/` и не коммитить его без проверки лицензии.

## Главная метрика

`false_correction_rate`: доля правильных KEEP-групп, где модель выбрала другое слово. В `utility` такая ошибка штрафуется в 3 раза сильнее обычного промаха.

## GitHub

- `validate.yml` — тесты и dataset smoke на обычном GitHub runner.
- `smoke-train-cpu.yml` — ручной маленький CPU train.
- `train-gpu.yml` — основной train на self-hosted GPU runner с labels `self-hosted,linux,x64,gpu`.

GitHub-hosted CPU runner не используется для тяжёлого ML. GitHub управляет экспериментом, артефактами и историей; вычисление делает GPU runner.

## Диагностическая проверка на старом SAGE testset

Существующий sentence-level testset нельзя считать train для Ranker. Но из него можно извлечь простые 1:1 замены слов для отдельной диагностики:

```bash
python scripts/extract_ranker_eval_from_sage.py \
  --input data/eval/SAGE95M_Russian_Testset_100KB_v1.txt \
  --out data/eval/ranker_external.jsonl
rsk-eval --checkpoint artifacts/ranker_v0/best.pt \
  --tokenizer artifacts/ranker_v0/tokenizer.json \
  --data data/eval/ranker_external.jsonl
```

Это только word-ranking diagnostic; он не измеряет пунктуацию, согласование или полное GEC.
