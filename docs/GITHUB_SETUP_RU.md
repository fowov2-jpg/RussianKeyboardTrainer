# GitHub setup

## 1. Репозиторий
Используется отдельный repo `RussianKeyboardTrainer`. Репозиторий может быть public, но в него нельзя коммитить пользовательские данные, приватные корпуса, ключи и модели/датасеты с несовместимой лицензией.

## 2. Первый push
Проект поддерживается через GitHub; обычный локальный push также возможен через стандартный git workflow.

## 3. CPU CI
`validate.yml` запускается на GitHub-hosted runner. Он не обучает production-модель.

## 4. GPU runner
Основной workflow ожидает labels:

`self-hosted`, `linux`, `x64`, `gpu`

В GitHub: Settings → Actions → Runners → New self-hosted runner. Использовать инструкции GitHub для ОС runner-а. Для текущих actions на Node 24 runner должен быть версии не ниже 2.327.1.

После регистрации добавить custom label `gpu`.

## 5. Данные
Production dataset НЕ хранить в git, если это запрещено лицензией или он содержит пользовательские данные. Варианты:
- заранее подготовить dataset локально на GPU runner;
- скачивать разрешённый corpus из официального источника отдельным setup step;
- хранить только метаданные/хэши/split manifests.

## 6. Запуск
Actions → `train-ranker-gpu` → Run workflow.

Первый production эксперимент рекомендуется запускать с 50k synthetic + 40k KEEP groups. После оценки FCR переходить к hard-negative mining и только потом увеличивать dataset.
