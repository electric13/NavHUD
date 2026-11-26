# NavHUD & WeCarFlow HUD Sync Walkthrough

Реализовано полное воссоздание приложения **`NavHUD`** (`com.e8.navhud`) с нуля на Kotlin + Gradle и изолированное дооснащение канала `ACTION_GEELY_NAVHUD_UPDATE` в **`WeCarFlow`** (`GeelyMediaBridge.kt`), чтобы отображение названия трека, исполнителя и обложки на проекционном дисплее (HUD) полностью повторяло логику **DimMenu** без каких-либо изменений в DimMenu, `mediacenterservice` и `onlineservice`.

---

## 1. Изменения в WeCarFlow (DimMenu не затронут)

- В `GeelyMediaBridge.kt` (репозиторий WeCarFlow) добавлены изолированные helpers для проекции (`ACTION_GEELY_NAVHUD_UPDATE = "com.geely.navhud.metadata_update"` и ответ на `ACTION_NAVHUD_REQUEST_METADATA = "com.geely.navhud.request_metadata"`):
  - `computeHudSignature(state, resolvedCoverUri)` — отслеживает состояние HUD независимо от `lastBroadcastSignature` для DimMenu.
  - `encodeHudCoverBytes(bitmap, rawCoverBytes)` — масштабирует обложку до максимум `128x128` и сжимает в компактный JPEG (`quality = 85`) для передачи в extra `cover_bytes` поверх Intent IPC (решает проблему `file://` / Scoped Storage для локальных USB-обложек, `content://` URI и Bluetooth AVRCP `coverBytes`).
  - `syncNavHudMetadata` и `clearNavHudMetadata`:
    - Ветка `SourceType.BLUETOOTH`: оставляет бродкасты DimMenu (`ACTION_GEELY_METADATA_CHANGED`) без изменений, но для HUD теперь отправляет обновления при смене/очистке BT-трека **даже при пустом URL обложки**, а также передает `cover_bytes` и очищает HUD при `DISCONNECTED` / `STOPPED` / статус-плейсхолдерах.
    - Ветка `sendFullSystemBroadcasts` (USB, Radio, SoundCloud, Spotify, YandexMusic): передает на HUD как строковый `cover` URI, так и подготовленный `cover_bytes`.
- Расширены модульные тесты в `GeelyMediaBridgeTest.kt`.

---

## 2. Воссозданный проект NavHUD

### Модуль `:car-stubs` (compile-only SDK-заглушки Geely / AOSP)
- [`ServiceManager.java`](../car-stubs/src/main/java/android/os/ServiceManager.java)
- [`IConnectable.java`](../car-stubs/src/main/java/com/geely/platformapi/binder/IConnectable.java), [`Car.java`](../car-stubs/src/main/java/com/geely/platformapi/car/Car.java), [`ICar.java`](../car-stubs/src/main/java/com/geely/platformapi/car/ICar.java), [`ISensor.java`](../car-stubs/src/main/java/com/geely/platformapi/car/sensor/ISensor.java)
- [`GeelyCar.java`](../car-stubs/src/main/java/geely/car/GeelyCar.java), [`IGeelyCar.java`](../car-stubs/src/main/java/geely/car/IGeelyCar.java), [`CarSignalManager.java`](../car-stubs/src/main/java/geely/car/hardware/signal/CarSignalManager.java)

### Модуль `:app` (`com.e8.navhud`, `sharedUserId="android.uid.system"`)
- **Единый музыкальный канал от WeCarFlow** ([`HudMediaController.kt`](../app/src/main/java/com/e8/navhud/HudMediaController.kt), [`HudService.kt`](../app/src/main/java/com/e8/navhud/HudService.kt)):
  - Полностью удалены `MUSIC_PKGS` (`com.spotify.music`, `ru.yandex.music`, `ru.yandex.yandexnavi`, `com.android.bluetooth`) и все слушатели `MediaSessionManager` из `HudService` и `NavNotificationListener`.
  - Приём музыки идет исключительно через `BroadcastReceiver` (`RECEIVER_EXPORTED` на API 33+) на экшен `com.geely.navhud.metadata_update`, плюс handshake-запрос `com.geely.navhud.request_metadata` при старте/пересоздании оверлея.
  - Исправлено залипание старой обложки: если новый (или обновленный) трек пришел без `cover_bytes` и без `cover` URI, `applyArtBitmap(null)` вызывается безусловно; при смене трека с медленным URI старая обложка сбрасывается сразу, а просроченные асинхронные колбэки отбрасываются по `currentArtToken`.
  - Приоритет декодирования: сначала `cover_bytes`, при их отсутствии или сбое — загрузка `cover` URI (`content://`, `file://`, `android.resource://`, `http://`, `https://`).
  - После `SCREEN_ON` и пересоздания оверлея вызывается `replayLastState()` и `requestHudMetadata()` для восстановления текущего трека и обложки.
- **Полное сохранение навигационного и автомобильного функционала**:
  - Все 3 канала Яндекс.Навигатора: [`NavAccessibilityService.kt`](../app/src/main/java/com/e8/navhud/NavAccessibilityService.kt) + [`NavAccessibilityParser.kt`](../app/src/main/java/com/e8/navhud/NavAccessibilityParser.kt) (с исправлением Unicode-флага `(?iu)` в регулярках разбиения дистанции с заглавной кириллической буквы), [`NavNotificationListener.kt`](../app/src/main/java/com/e8/navhud/NavNotificationListener.kt) (только навигация YaN) и [`NavInfoPoller.kt`](../app/src/main/java/com/e8/navhud/NavInfoPoller.kt).
  - Векторные стрелки маневров и fallback-глифы в [`ManeuverMapper.kt`](../app/src/main/java/com/e8/navhud/ManeuverMapper.kt) и оригинальные XML-ресурсы [`view_hud_display.xml`](../app/src/main/res/layout/view_hud_display.xml).
  - Оверлей на второй дисплей (`displayId == 2` / `800x480`), часы `HH:mm`, опрос ВВБ SoC % (`0x400600`), опрос поворотников через `CarSignalManager` (`80 мс` + hold `700 мс` + анимация), `keepYanAlive`, отключение микрофона ЯН во время телефонного звонка, [`BootReceiver.kt`](../app/src/main/java/com/e8/navhud/BootReceiver.kt) и авто-восстановление A11y/NLS через [`SystemSettingsEnabler.kt`](../app/src/main/java/com/e8/navhud/SystemSettingsEnabler.kt).

---

## 3. Результаты проверки и R8

### Модульные тесты и сборки
- **NavHUD**: `./gradlew :app:testDebugUnitTest :app:assembleRelease :app:assembleDebug` — успешно; прошли оба тестовых набора ([`HudMediaControllerTest.kt`](../app/src/test/java/com/e8/navhud/HudMediaControllerTest.kt): 6 тестов; [`NavAccessibilityParserTest.kt`](../app/src/test/java/com/e8/navhud/NavAccessibilityParserTest.kt): 3 теста), итого **9/9** без ошибок и пропусков.

### Подпись, R8-оптимизация и выходные APK
Включены `isMinifyEnabled = true` и `isShrinkResources = true` в `release`-сборке `NavHUD` ([`app/build.gradle.kts`](../app/build.gradle.kts), [`proguard-rules.pro`](../app/proguard-rules.pro)). Размер `NavHUD` APK уменьшился с **1.8M** до **~105K**.

Аудит `resources.txt`, `seeds.txt`, `dexdump -d` и декомпиляции собранного R8 APK подтвердил:
- Сохранены все 5 системных компонентов манифеста (`MainActivity`, `HudService`, `BootReceiver`, `NavAccessibilityService`, `NavNotificationListener`), `HudMediaController`, все `BroadcastReceiver`, фоновые таймеры и обход ограничений Яндекс.Навигатора.
- Сохранены все вызовы платформенных/Geely API (`ServiceManager.getService("geelycar_service")`, `GeelyCar.createCar`, `CarSignalManager.getExtrLtgStsTurnIndrLe/Ri`, `Car.create`, `IConnectable.connect/disconnect`, `ISensor.getSensorLatestValue`).
- Сохранены `view_hud_display.xml`, все 19 `@id/hud*`, 6 системных HUD drawable и все 13 иконок маневров `ManeuverMapper`. Удалены только неиспользуемые ресурсы AndroidX и неиспользуемые макеты/иконки.
- Подтверждена подпись стандартным AOSP `platform` сертификатом (`SHA-1: 27196e386b875e76adf700e7ea84e4c6eee33dfa`) и живая работа на головном устройстве Geely E8 (Display 2 `800×480`): навигация, заряд ВВБ, поворотники, отображение и обновление обложек/треков и восстановление при перезапуске.
