# RussianKeyboardTrainer 1.1.0 CoGo

Trainer для первой собственной малой модели Russian Smart Keyboard 1.8.x — Context Ranker.

## Что обучаем

Не генеративную LM. Модель получает `[левый контекст] + [набранное слово] + [кандидат]` и выдаёт один score. Кандидаты ранжируются внутри группы; один кандидат всегда KEEP.

Архитектура v0: char-level TransformerEncoder, 3 слоя, d_model=128, max_chars=96. Внешний pretrained checkpoint не требуется.

## Что изменилось в 1.1.0

- listwise cross-entropy вместо независимого BCE по кандидатам;
- KEEP-margin calibration на validation для ограничения false correction rate;
- `keep_margin` сохраняется в checkpoint и ONNX metadata;
- clean sentences делятся на train/val/test ДО генерации synthetic/KEEP, поэтому исходное предложение не течёт между split;
- CI делает 1-epoch listwise smoke-train на каждом push.

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

Для реального обучения чистый русский корпус хранится локально и не коммитится без проверки лицензии.

## Главная метрика

`false_correction_rate` — доля правильных KEEP-групп, где модель выбрала другое слово. Конфиги по умолчанию калибруют KEEP margin под целевой validation FCR <= 0.5%.

## GitHub

- `validate.yml` — tests + dataset build + 1-epoch listwise smoke;
- `smoke-train-cpu.yml` — ручной CPU smoke;
- `train-gpu.yml` — основной train на self-hosted GPU runner (`self-hosted,linux,x64,gpu`);
- `sweep-gpu.yml` — параллельный small/base/wide sweep.

GitHub-hosted CPU runner не используется для тяжёлого ML. GitHub управляет экспериментом и артефактами, вычисление делает GPU runner.

## External SAGE diagnostics

Существующие SAGE testset нельзя использовать как train для Ranker. В public-репозитории eval-корпуса не хранятся: они подключаются локально через `data/eval/` и используются только для независимой диагностики.
