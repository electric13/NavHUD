# План: Воссоздание `NavHUD` на Kotlin и синхронизация с `WeCarFlow` по логике DimMenu

## Goal Description

Воссоздать приложение проекционного дисплея **`com.e8.navhud`** с нуля в виде чистого Kotlin + Gradle проекта с полным сохранением всего исходного функционала (указатели Яндекс.Навигатора, заряд ВВБ, поворотники, часы, анти-kill Навигатора, управление микрофоном при звонке), удалив жестко зашитый список музыкальных пакетов (`MUSIC_PKGS`) и прямое прослушивание `MediaSession`.

Музыка на HUD будет управляться **исключительно** через единый канал от `WeCarFlow` (`com.geely.navhud.metadata_update`), полностью повторяя логику **DimMenu**, **без вмешательства** в `DimMenu`, `mediacenterservice`, `onlineservice` и логику внешних плееров.

---

## Ответ на вопрос: зачем в `MUSIC_PKGS` был `ru.yandex.yandexnavi`

Внутри Яндекс.Навигатора встроен мини-плеер Яндекс.Музыки (`ru.yandex.yandexnavi`). При воспроизведении музыки прямо из Навигатора системная `MediaSession` публикуется от пакета `ru.yandex.yandexnavi`. Автор оригинального `NavHUD` добавил его в `MUSIC_PKGS` с низшим приоритетом после `com.spotify.music` и `ru.yandex.music`, чтобы отображать треки, запущенные из Навигатора. В нашей архитектуре с `WeCarFlow` этот список больше не нужен.

---

## User Review Required

> [!IMPORTANT]
> - Логика **DimMenu** (`ACTION_GEELY_METADATA_CHANGED` в `GeelyMediaBridge.kt`), `mediacenterservice` и `onlineservice` **не затрагиваются**.
> - В `WeCarFlow` вносятся только изолированные улучшения в отправку **`ACTION_GEELY_NAVHUD_UPDATE`** (отдельный интент для HUD), чтобы он 1-в-1 соответствовал тому, что видит пользователь в DimMenu (включая Bluetooth без URL-обложки и передачу `cover_bytes` для локальных/USB обложек).
> - Исходные декомпилированные файлы и оригинальный `com.e8.navhud.apk` остаются нетронутыми для сверки.

---

## Proposed Changes

### Компонент 1: Новый Kotlin/Gradle проект `NavHUD` (`com.e8.navhud`)

Создается самостоятельный Android Gradle-проект (подписывается системным `platform`-ключом через `local.properties` / переменные окружения под `android:sharedUserId="android.uid.system"`). При переносе логики сверяемся с `jadx`-декомпиляцией `com.e8.navhud.apk` и системными JAR прошивки (`geely.car.jar`, `geely.platformapi.jar`, `android.car.jar`) для 100% совпадения сигнатур и поведения.

#### [NEW] Структура проекта
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` (поддержка системного `platform`-ключа через `local.properties`).
- **Compile-only стабы Geely/Car API** (`navhud/libs` или `navhud/car-stubs/`):
  - `android.os.ServiceManager`
  - `geely.car.IGeelyCar`, `geely.car.GeelyCar`, `geely.car.hardware.signal.CarSignalManager` (`getExtrLtgStsTurnIndrLe()`, `getExtrLtgStsTurnIndrRi()`)
  - `com.geely.platformapi.car.Car`, `ICar`, `sensor.ISensor`, `binder.IConnectable`
- **[NEW] `navhud/src/main/AndroidManifest.xml`**:
  - `android:sharedUserId="android.uid.system"`, все системные разрешения (`SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `RECEIVE_BOOT_COMPLETED`, `WRITE_SECURE_SETTINGS`, `READ_PHONE_STATE`, `CAR_ENERGY_PORTS`, `CAR_EXTERIOR_LIGHTS`, а также `READ_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES` для чтения `file://` URI как fallback).
  - `<queries>`: `ru.yandex.yandexnavi` и `com.tencent.wecarflow`.
  - Компоненты: `MainActivity`, `HudService`, `BootReceiver`, `NavNotificationListener`, `NavAccessibilityService`.
- **[NEW] Ресурсы (`navhud/src/main/res/`)**:
  - Перенос без изменений всех оригинальных макетов и графики: `view_hud_display.xml`, `view_hud_panel.xml`, `nav_accessibility_config.xml`, все векторные иконки маневров (`man_directions_*.xml`), `hud_battery_frame.xml`, `hud_battery_fill.xml`, `hud_turn_left.xml`, `hud_turn_right.xml`, `hud_display_bg.xml`, `speed_limit_circle.xml`, `ic_bubble.png`, `strings.xml`, `styles.xml`.
- **[NEW] Код приложения (`navhud/src/main/java/com/e8/navhud/`)**:
  1. `MainActivity.kt` — проверка `canDrawOverlays()`, запуск `HudService` (`startForegroundService`), мгновенный `finish()`.
  2. `BootReceiver.kt` — `directBootAware`, задержки 8с (`LOCKED_BOOT_COMPLETED`) / 3с, автовключение `NavAccessibilityService` и запуск `HudService`.
  3. `ShellHelper.kt` — безопасный `execSh` (`/system/bin/sh -c`) и `exec` (`su`) с таймаутом 10с.
  4. `NavData.kt` — модель маршрута и навигационных полей (`hasRoute`, `hasSpeed`).
  5. `NavAccessibilityService.kt` — 1-в-1 логика парсинга дерева `ru.yandex.yandexnavi` (500 мс throttle + 2000 мс poll, `MANEUVER_KEYWORDS`, `UI_NOISE`, расстояния, улицы, ограничение скорости, ETA).
  6. `NavInfoPoller.kt` — 3с shell-фоллбэк через `dumpsys notification --noredact` для `ru.yandex.yandexnavi`.
  7. `NavNotificationListener.kt` — **только** уведомления навигации (`onNavUpdate`, `onNavGone` для `ru.yandex.yandexnavi`). Полностью удалены `MediaSessionManager`, `MUSIC_PKGS`, `onMusicUpdate`, `onMusicGone`.
  8. `HudService.kt`:
     - Вся навигационная часть: поиск дисплея (`id=2` -> `800x480` -> `id!=0`), оверлей `TYPE_APPLICATION_OVERLAY` / `TYPE_SYSTEM_OVERLAY`, восстановление по `SCREEN_ON`, векторные и текстовые стрелки маневров (`setHudArrow`, `getManeuverArrow`), 20с автоскрытие.
     - Телеметрия Geely: `socPollRunnable` (10с, `0x400600`), `turnPollRunnable` (80 мс, `CarSignalManager`, 700 мс hold + 90 мс alpha animation), часы (`HH:mm`).
     - Системные службы: `keepYanAlive` (30с), `micPollRunnable` (2с), `a11yObserver` + ретраи A11y/NLS.
     - **Чистая подсистема музыки (`navHudMediaReceiver`)**:
       - Регистрирует `BroadcastReceiver` на `"com.geely.navhud.metadata_update"` (`RECEIVER_EXPORTED` на Android 13+).
       - Никаких `MUSIC_PKGS` и `MediaSessionManager`.
       - Если `title` пустой или только пробелы -> `clearMusicDisplay()` (сброс текстов, `hudMusicRow = GONE`, `applyArt(null)`).
       - Если `title` не пустой -> `updateMusicDisplay(title, artist)` и загрузка обложки:
         - Приоритет 1: `cover_bytes` (`ByteArray` JPEG от `WeCarFlow`), если хэш изменился — декодируем `Bitmap` и вызываем `applyArt(bmp)`.
         - Приоритет 2: `cover` URI (`http://`, `https://`, `content://`, `file://`) в фоновом потоке с защитой `currentArtToken` от гонок.
         - Если нет ни `cover_bytes`, ни `cover` URI -> **обязательный `applyArt(null)`**, чтобы не оставалась картинка от прошлого трека.

---

### Компонент 2: Изолированные доработки `ACTION_GEELY_NAVHUD_UPDATE` в `WeCarFlow`

#### [MODIFY] `GeelyMediaBridge.kt` (репозиторий WeCarFlow)
1. **Не изменяя логику DimMenu (`ACTION_GEELY_METADATA_CHANGED`)**, добавить подготовку компактных `cover_bytes` (JPEG <= 128x128) из `artBitmap` (или `coverBytes`) и передачу их в `ACTION_GEELY_NAVHUD_UPDATE` как extra `"cover_bytes"`, сохраняя `"cover"` URI.
2. **Bluetooth для HUD**:
   - Вынести отправку `ACTION_GEELY_NAVHUD_UPDATE` из условия `coverUriStr.isNotBlank()` внутри ветки `SourceType.BLUETOOTH`.
   - Использовать отдельную сигнатуру `lastHudSignature`, чтобы отправлять обновление на HUD при любом изменении BT-трека/исполнителя/обложки/статуса, **даже если обложки еще нет** (тогда на HUD обновится текст BT-трека и очистится обложка от предыдущего источника), при этом `ACTION_GEELY_METADATA_CHANGED` для DimMenu срабатывает ровно по прежним правилам.
3. Добавить чистые вспомогательные методы (например, `buildHudIntent(...)`) с unit-тестами.

#### [MODIFY] `GeelyMediaBridgeTest.kt` (репозиторий WeCarFlow)
- Добавить тесты для новой логики формирования HUD-интентов и сигнатур (включая Bluetooth без обложки и смену трека с обложки на трек без обложки).

---

## Verification Plan

### Automated Tests
1. Сборка и проверка нового проекта `NavHUD`:
   ```bash
   ./gradlew :app:testDebugUnitTest :app:assembleRelease
   ```
2. Проверка подписи `NavHUD` тем же сертификатом AOSP `platform`:
   ```bash
   "$ANDROID_HOME"/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```

### Manual Verification (на автомобиле Geely E8 и эмуляторе)
1. **На эмуляторе**: установить `WeCarFlow` и `NavHUD`, проверить через `adb logcat` корректный прием `com.geely.navhud.metadata_update`, декодирование `cover_bytes`/`cover` URI и отображение/очистку `hudMusicRow`.
2. **На автомобиле (проверка сохранения старого функционала `NavHUD` + музыка как в DimMenu)**:
   - Указатели поворотов/маневров Яндекс.Навигатора (`NavAccessibilityService` / `NavNotificationListener` / `NavInfoPoller`), знаки скорости, ETA, улицы.
   - Поворотники (левый/правый/аварийка) и заряд батареи (SoC %).
   - Переключение всех источников в `WeCarFlow` (Радио, USB/MicroSD, SoundCloud, Bluetooth, Spotify, Я.Музыка): обложка и название трека на HUD полностью соответствуют DimMenu; при переключении со Spotify на другой источник или при плейсхолдере/остановке обложка Spotify не залипает.
