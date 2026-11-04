# Русская клавиатура rc9-local — без чтения экрана

[Скачать ARM64 для современных POCO и других телефонов](https://raw.githubusercontent.com/fowov2-jpg/RussianKeyboardTrainer/apk-rc9-local/RussianKeyboard-1.0.0-rc9-local-ARM64.zip)

[Скачать ARM32 для старых устройств](https://raw.githubusercontent.com/fowov2-jpg/RussianKeyboardTrainer/apk-rc9-local/RussianKeyboard-1.0.0-rc9-local-ARM32.zip)

Скачайте ZIP, распакуйте в «Файлах» и откройте APK. Вход в GitHub не нужен.

Это отдельный вариант без сервиса Accessibility. Он исправляет набираемый вами текст, пунктуацию и опечатки, но не читает экран и сообщения собеседника. Контекст можно задать вручную. Полная версия с видимым чатом сохранена в исходниках.

Версия 1.0.0-rc9-local, versionCode=10. Исходный коммит 1644ad8. Локальная подпись совпадает с ранее выданным локальным rc8. Сборка не является debuggable. В APK нет запрашиваемых разрешений и сервиса BIND_ACCESSIBILITY_SERVICE; клавиатурный сервис и офлайн-модели сохранены.

Локально прошли 25610 JUnit-случаев, lint двух вариантов, сборка 3 ABI, компиляция Android-тестов, проверка подписи, zipalign и упакованных активов. Состав всех standalone-APK проверен по итоговым манифестам и ресурсам.

**Одобрение Play Защиты и установка на вашем телефоне не подтверждены.** Точная причина исходной блокировки неизвестна. Если эта версия тоже блокируется, нужен полный текст предупреждения Play Защиты. Отключать защиту для диагностической проверки не требуется.

[Подробности и воспроизведение](https://github.com/fowov2-jpg/RussianKeyboardTrainer/blob/feat/context-phrase-rc3-20261104/docs/INSTALLATION_RC9_RU.md) · [Код и проверки](https://github.com/fowov2-jpg/RussianKeyboardTrainer/pull/6)
