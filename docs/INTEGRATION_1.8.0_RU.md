# Интеграция с RussianSmartKeyboard 1.8.0

Trainer выдаёт `ranker_int8.onnx` + `tokenizer.json`.

Контракт приложения уже определён интерфейсом `ContextCandidateRanker`:

- вход: `leftContext` и список candidate;
- выход: `Ranked(text, score)`;
- `KEEP` представлен кандидатом, совпадающим с уже набранным словом;
- Android-реализация должна батчить кандидаты одного слова в один ONNX Runtime run;
- при недостаточной разнице между top-1 и KEEP приложение обязано оставить исходный текст.

Для первой интеграции рекомендуется calibrate threshold на отдельном safety testset. Цель — минимальный False Correction Rate, а не максимальная агрессивность.
