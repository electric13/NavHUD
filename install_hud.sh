#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG="com.e8.navhud"
MAIN_ACTIVITY="com.e8.navhud/.MainActivity"
HUD_SERVICE="com.e8.navhud/.HudService"
A11Y_COMP="com.e8.navhud/com.e8.navhud.NavAccessibilityService"
NLS_COMP="com.e8.navhud/com.e8.navhud.NavNotificationListener"
DEFAULT_APK="$SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk"

BUILD_FIRST=0
SHOW_LOGS=0
SERIAL="${ANDROID_SERIAL:-}"
APK_PATH="$DEFAULT_APK"

usage() {
    cat <<'EOF'
Использование: ./install_hud.sh [опции]

Опции:
  -b, --build          Пересобрать release APK (R8) перед установкой
  -l, --logs           Открыть logcat (NavHUD + GeelyMediaBridge) после запуска
  -s, --serial <id>    Указать ADB serial устройства (по умолчанию ANDROID_SERIAL)
  --apk <path>         Путь к APK (по умолчанию app/build/outputs/apk/release/app-release.apk)
  -h, --help           Показать справку
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -b|--build)
            BUILD_FIRST=1
            shift
            ;;
        -l|--logs)
            SHOW_LOGS=1
            shift
            ;;
        -s|--serial)
            SERIAL="${2:-}"
            [[ -n "$SERIAL" ]] || { echo "Ошибка: укажите serial после $1" >&2; exit 1; }
            shift 2
            ;;
        --apk)
            APK_PATH="${2:-}"
            [[ -n "$APK_PATH" ]] || { echo "Ошибка: укажите путь после --apk" >&2; exit 1; }
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Неизвестная опция: $1" >&2
            usage
            exit 1
            ;;
    esac
done

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB=(adb -s "$SERIAL")
fi

if [[ "$BUILD_FIRST" -eq 1 || ! -f "$APK_PATH" ]]; then
    if [[ "$APK_PATH" != "$DEFAULT_APK" && ! -f "$APK_PATH" ]]; then
        echo "Ошибка: указанный APK не найден: $APK_PATH" >&2
        exit 1
    fi
    echo "[1/5] Сборка NavHUD Release (R8)..."
    (cd "$SCRIPT_DIR" && ./gradlew :app:assembleRelease)
fi

[[ -f "$APK_PATH" ]] || { echo "Ошибка: APK не найден: $APK_PATH" >&2; exit 1; }

echo "[2/5] Проверка подключения ADB..."
DEVICE_STATE=$("${ADB[@]}" get-state 2>/dev/null || true)
if [[ "$DEVICE_STATE" != "device" ]]; then
    echo "Устройство не подключено или найдено несколько устройств без -s:" >&2
    adb devices -l >&2 || true
    exit 1
fi

APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
echo "[3/5] Установка поверх ($PKG, $APK_SIZE): $APK_PATH"
"${ADB[@]}" install -r -d "$APK_PATH"

echo "[4/5] Проверка прав (SYSTEM_ALERT_WINDOW / AppOps) и запуск $MAIN_ACTIVITY..."
"${ADB[@]}" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
"${ADB[@]}" shell dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1 || true
"${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY"

sleep 2

echo "[5/5] Проверка состояния сервиса, A11y и NLS..."
PID=$("${ADB[@]}" shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
A11Y_VAL=$("${ADB[@]}" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r' || true)
NLS_VAL=$("${ADB[@]}" shell settings get secure enabled_notification_listeners 2>/dev/null | tr -d '\r' || true)
SVC_RUNNING=$("${ADB[@]}" shell dumpsys activity services "$PKG" 2>/dev/null | grep -c "ServiceRecord.*HudService" || true)

echo "  PID процесса      : ${PID:-НЕ ЗАПУЩЕН}"
echo "  HudService активен: ${SVC_RUNNING:-0}"
echo "  Accessibility     : $([[ "$A11Y_VAL" == *"$A11Y_COMP"* ]] && echo 'OK' || echo "НЕТ ($A11Y_VAL)")"
echo "  NotifListener     : $([[ "$NLS_VAL" == *"$NLS_COMP"* ]] && echo 'OK' || echo "НЕТ ($NLS_VAL)")"

if [[ -z "$PID" || "${SVC_RUNNING:-0}" -eq 0 ]]; then
    echo "HudService пока не активен; пробую повторно запустить $MAIN_ACTIVITY..."
    "${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null 2>&1 || true
    echo "Если оверлей не появился, проверьте разрешение 'Поверх других окон' для NavHUD или откройте логи (-l)."
fi

if [[ "$SHOW_LOGS" -eq 1 ]]; then
    echo "Открываю logcat (Ctrl+C для выхода)..."
    exec "${ADB[@]}" logcat -v time -s NavHUD_Svc NavHUD_A11y NavHUD_NLS NavHUD_Poller NavHUD_Boot GeelyMediaBridge
fi
