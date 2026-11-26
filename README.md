# NavHUD (`com.e8.navhud`) — проекционный дисплей (HUD) для Geely E8

Автономный Android-проект (`Kotlin` + `Gradle`), воссозданный с нуля на основе реверс-инжиниринга оригинального `com.e8.navhud` и адаптированный для синхронизации метаданных и обложек треков с плеером **WeCarFlow** (`com.tencent.wecarflow`) по логике DimMenu.

---

## Установка готовой сборки (для пользователей)

1. Откройте раздел **[Releases](https://github.com/electric13/NavHUD/releases)** этого репозитория и скачайте оптимизированный R8 APK (`NavHUD-v*-release.apk`, ~105 КБ).
2. Установите APK на головное устройство по ADB поверх текущей версии или с нуля:
   ```bash
   ./install_hud.sh --apk /path/to/NavHUD-v2.0-release.apk
   ```
   или вручную:
   ```bash
   adb install -r -d NavHUD-v2.0-release.apk
   adb shell appops set com.e8.navhud SYSTEM_ALERT_WINDOW allow
   adb shell dumpsys deviceidle whitelist +com.e8.navhud
   adb shell am start -W -n com.e8.navhud/.MainActivity
   ```

> **Примечание о системной подписи:** приложение использует `android:sharedUserId="android.uid.system"`. Чтобы оно устанавливалось и работало с системными правами на ГУ Geely E8, релизный APK должен быть подписан тем же AOSP `platform`-сертификатом прошивки (`SHA-1: 27196e386b875e76adf700e7ea84e4c6eee33dfa`).

> Для отображения обложек и метаданных музыки на HUD на ГУ также должен быть установлен совместимый плеер `WeCarFlow`, отправляющий broadcast `com.geely.navhud.metadata_update`.

---

## Сборка и тестирование из исходников

### 1. Конфигурация локальной среды

Скопируйте шаблон [`local.properties.example`](local.properties.example) в `local.properties` (файл добавлен в `.gitignore`) и укажите путь к Android SDK (а для локальной подписи `platform`-ключом — параметры своего PKCS12 keystore):

```bash
cp local.properties.example local.properties
```

Альтернативно SDK и параметры подписи можно передавать через переменные окружения `ANDROID_HOME` / `ANDROID_SDK_ROOT` и `NAVHUD_KEYSTORE_PATH`, `NAVHUD_KEYSTORE_PASSWORD`, `NAVHUD_KEY_ALIAS`, `NAVHUD_KEY_PASSWORD`.

### 2. Команды сборки

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

- **Release APK (R8, `isMinifyEnabled = true` + `isShrinkResources = true`)**: `app/build/outputs/apk/release/app-release.apk` (~105 КБ; без указанного keystore собирается `app-release-unsigned.apk`).
- **Debug APK (без R8)**: `app/build/outputs/apk/debug/app-debug.apk`.
- **Быстрая установка после локальной сборки**:
  ```bash
  ./install_hud.sh -b -l    # пересобрать Release R8 + установить по ADB + открыть logcat
  ```

---

## Публикация готовых R8 APK на GitHub (Releases + CI)

В репозитории настроен workflow [`.github/workflows/release.yml`](.github/workflows/release.yml):

1. При каждом `push` / `pull_request` автоматически выполняются unit-тесты (`:app:testDebugUnitTest`) и контрольная сборка R8 (`:app:assembleRelease`).
2. При создании тега вида `v*` (например, `git tag v2.1 && git push origin v2.1`) GitHub Actions автоматически собирает R8 APK и создаёт **GitHub Release** с прикреплённым `.apk` и `.sha256`.
   - Чтобы релизный APK из Actions автоматически подписывался системным `platform`-ключом прошивки, добавьте в *Settings -> Secrets and variables -> Actions* четыре секрета: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
   - Либо вы всегда можете приложить локально подписанный `app-release.apk` вручную через *Releases -> Draft a new release*.

---

## Почему R8 APK весит ~105 КБ вместо ~4.7 МБ?

| Метрика | Оригинальный APK | Воссозданный `NavHUD` (R8) |
|---|---|---|
| Размер APK-файла | ~4.7 МБ (10.5 МБ распакованный, 918 файлов) | **~105 КБ** (216 КБ распакованный, 51 файл) |
| `classes.dex` | 8.6 МБ (`1809` классов без R8) | **~114 КБ** (`121` класс после tree-shaking) |
| `resources.arsc` | 1.03 МБ (~850 файлов `abc_*`, `mtrl_*`, `m3_*`) | **~8.6 КБ** (только ресурсы HUD-дисплея) |
| Лишние библиотеки | `material`, `appcompat`, `constraintlayout`, `coroutines`, весь `kotlin-stdlib` | Только `android.app.Activity` + нужная часть `androidx.core:core-ktx` и stdlib |

Весь функционал (Яндекс.Навигатор по 3 каналам, SoC батареи, поворотники, часы, keep-alive, микрофон при звонке и приём музыки от WeCarFlow) сохранён — удалён только неиспользуемый код Material/AppCompat/Coroutines.

---

## Структура проекта

- **`:app`** (`com.e8.navhud`)
  - [`HudService.kt`](app/src/main/java/com/e8/navhud/HudService.kt) — передний сервис оверлея на второй экран (`displayId == 2` / `800x480`), часы, SoC (`0x400600`), поворотники (`CarSignalManager`), `SCREEN_ON`, keep-alive ЯН и подавление микрофона ЯН во время звонка.
  - [`HudMediaController.kt`](app/src/main/java/com/e8/navhud/HudMediaController.kt) — приём `com.geely.navhud.metadata_update` и запрос `com.geely.navhud.request_metadata`: поддержка `cover_bytes` + `cover` URI, принудительный `applyArt(null)` при отсутствии обложки, защита от гонок `currentArtToken`.
  - [`NavAccessibilityService.kt`](app/src/main/java/com/e8/navhud/NavAccessibilityService.kt) и [`NavAccessibilityParser.kt`](app/src/main/java/com/e8/navhud/NavAccessibilityParser.kt) — чтение дерева UI Яндекс.Навигатора (скорость, лимит, маневр, дистанция, улицы, ETA).
  - [`NavNotificationListener.kt`](app/src/main/java/com/e8/navhud/NavNotificationListener.kt) и [`NavInfoPoller.kt`](app/src/main/java/com/e8/navhud/NavInfoPoller.kt) — навигационные уведомления и fallback `dumpsys notification` (без `MediaSessionManager`).
  - [`ManeuverMapper.kt`](app/src/main/java/com/e8/navhud/ManeuverMapper.kt) — маппинг маневров на векторные `man_directions_*` и Unicode-стрелки.
  - [`BootReceiver.kt`](app/src/main/java/com/e8/navhud/BootReceiver.kt), [`MainActivity.kt`](app/src/main/java/com/e8/navhud/MainActivity.kt), [`ShellHelper.kt`](app/src/main/java/com/e8/navhud/ShellHelper.kt), [`SystemSettingsEnabler.kt`](app/src/main/java/com/e8/navhud/SystemSettingsEnabler.kt).
- **`:car-stubs`** — compile-only заглушки скрытых API (`android.os.ServiceManager`, `com.geely.platformapi.*`, `geely.car.*`), предоставляемых прошивкой на устройстве.
- **`docs/`** — документация исследования и архитектуры:
  - [`docs/navhud_overview.md`](docs/navhud_overview.md) — разбор возможностей оригинального `com.e8.navhud` и первопричин залипания обложки Spotify.
  - [`docs/implementation_plan.md`](docs/implementation_plan.md) — план воссоздания и контракта `WeCarFlow` <-> `NavHUD`.
  - [`docs/walkthrough.md`](docs/walkthrough.md) — журнал реализации, тесты и R8-аудит.

---

## Быстрая отладка через ADB

```bash
# Логи работы HUD и моста метаданных
adb logcat -v time -s NavHUD_Svc NavHUD_A11y NavHUD_NLS NavHUD_Poller NavHUD_Boot GeelyMediaBridge

# Тест отображения трека на HUD
adb shell am broadcast -a com.geely.navhud.metadata_update \
  --es title "Test Track" --es artist "Test Artist" --ez playing true

# Очистить музыку на HUD
adb shell am broadcast -a com.geely.navhud.metadata_update \
  --es title "" --es artist "" --es cover "" --ez playing false

# Проверка системных привязок и дисплеев
adb shell settings get secure enabled_accessibility_services
adb shell settings get secure enabled_notification_listeners
adb shell dumpsys display | grep -E "Display Id|mBaseDisplayInfo"
```
