# Скриншоты без личной переписки

Публичные изображения созданы из настоящих Android View в Robolectric Native Graphics. Они показывают интерфейс приложения, заполненный исключительно тестовыми данными. Это не фотографии и не снимки экрана пользовательского телефона.

| Файл | Метод в `DesignScreenshotTest` | Данные |
| --- | --- | --- |
| `images/feed-portrait.png` | `capturesPortraitFeed` | Шесть вымышленных сообщений: релиз, созвоны и семейный техсовет |
| `images/feed-landscape.png` | `capturesLandscapeFeedInChronologicalSingleColumn` | Семь вымышленных сообщений, сгенерированные аватары и миниатюра фотографии |
| `images/media-portrait.png` | `capturesPhotoAndVideoPreviewInDarkPanel` | Вымышленное оповещение и публичная фотография CC0 |
| `images/video-unavailable.png` | `capturesVideoWithoutPreview` | Искусственное уведомление без видеопревью |

## Воспроизведение

```powershell
.\gradlew.bat testDebugUnitTest --tests ru.telegramwall.app.DesignScreenshotTest --rerun-tasks
```

Результаты создаются в `outputs/screenshots/`. Для публикации используются только четыре указанных файла: `portrait.png`, `landscape.png`, `dark-media-portrait.png` и `dark-video-unavailable.png`, скопированные в `docs/images/` под именами из таблицы.

Имена, саркастичные диалоги и названия чатов написаны специально для демонстрации. Время публичных снимков зафиксировано в тестовой фикстуре. Тесты работают с изолированным хранилищем Robolectric и не подключаются к телефону или аккаунту Telegram. Демонстрационные данные и тестовые медиа не включаются в APK.

Фотография озера и горы Фудзи — работа lumoplank под CC0, полученная через тестовые материалы Stageshow. [Источник и сведения о лицензии](../THIRD_PARTY_NOTICES.md).

Диагностические каталоги `outputs/`, реальные выгрузки с устройств и локальные материалы разработки исключены из Git. Файлы в `docs/images/` просмотрены перед публикацией.
