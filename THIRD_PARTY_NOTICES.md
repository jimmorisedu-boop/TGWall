# Сторонние материалы

## Фотография в тестах и скриншотах

- Произведение: **Kawaguchiko, Part III - Kawaguchiko6782.jpg**.
- Автор: **lumoplank**.
- [Страница файла Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Kawaguchiko,_Part_III_-_Kawaguchiko6782.jpg).
- Лицензия: [CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/), подтверждена на странице файла.
- Используемый вариант получен из `benjitaylor/skills`, пример `stageshow/examples/wikipedia/kawaguchiko.jpg`: уменьшение до 440 × 309 и JPEG-сжатие.
- Локальный файл: `app/src/test/resources/notification-photo.jpg`. Используется только в тестах и иллюстрациях документации; в APK не включён.

Тестовое видео `notification-video.mp4` — локально созданный односекундный синий кадр 320 × 180, H.264, без звука. Чужих записей и переписки в нём нет.

## Gradle Wrapper

Скрипты и JAR стандартного Gradle Wrapper распространяются по Apache License 2.0. Тексты лицензии и уведомления Gradle находятся в `docs/licenses/`.

## Зависимости сборки и тестов

Android Gradle Plugin, JUnit и Robolectric загружаются из объявленных Maven-репозиториев. Их дистрибутивы не включены в этот репозиторий. В рабочем приложении используются классы Android SDK без добавленных библиотек аналитики или рекламы.

Название Telegram используется для обозначения совместимых источников уведомлений. Проект не является официальным продуктом Telegram.
