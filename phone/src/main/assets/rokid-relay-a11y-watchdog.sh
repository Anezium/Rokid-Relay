#!/system/bin/sh

PKG="com.anezium.rokidrelay.glasses"
SVC="com.anezium.rokidrelay.glasses/com.anezium.rokidrelay.glasses.RelayAccessibilityService"
MAIN="$PKG/.MainActivity"
NAME="rokid-relay-a11y-watchdog"
BASE="/data/local/tmp"
PIDFILE="$BASE/$NAME.pid"
LOGFILE="$BASE/$NAME.log"
HEARTBEAT="$BASE/$NAME.heartbeat"
VERSIONFILE="$BASE/$NAME.version"
VERSION="2026-07-02.2"
INTERVAL="${INTERVAL:-2}"
HOME_AFTER_REPAIR="${HOME_AFTER_REPAIR:-1}"

log_line() {
  echo "$(date '+%Y-%m-%dT%H:%M:%S%z') $*" >> "$LOGFILE"
}

app_pid() {
  pidof "$PKG" 2>/dev/null | tr -d '\r'
}

pid_cmdline() {
  tr '\000' ' ' < "/proc/$1/cmdline" 2>/dev/null
}

is_watchdog_pid() {
  pid="$1"
  cmdline="$(pid_cmdline "$pid")"
  case "$cmdline" in
    *"$NAME.sh run"*|*"$(basename "$0") run"*|*"$0 run"*) return 0 ;;
    *) return 1 ;;
  esac
}

is_watchdog_running() {
  if [ ! -f "$PIDFILE" ]; then
    return 1
  fi
  pid="$(cat "$PIDFILE" 2>/dev/null)"
  if [ -z "$pid" ]; then
    return 1
  fi
  kill -0 "$pid" 2>/dev/null && is_watchdog_pid "$pid"
}

service_present() {
  enabled="$1"
  case ":$enabled:" in
    *":$SVC:"*) return 0 ;;
    *) return 1 ;;
  esac
}

repair_once() {
  accessibility_enabled="$(settings get secure accessibility_enabled 2>/dev/null)"
  enabled_services="$(settings get secure enabled_accessibility_services 2>/dev/null)"
  case "$enabled_services" in
    ""|"null")
      next_services="$SVC"
      ;;
    *)
      if service_present "$enabled_services"; then
        next_services="$enabled_services"
      else
        next_services="$enabled_services:$SVC"
      fi
      ;;
  esac

  if [ "$enabled_services" != "$next_services" ]; then
    settings put secure enabled_accessibility_services "$next_services" 2>>"$LOGFILE"
  fi
  if [ "$accessibility_enabled" != "1" ]; then
    settings put secure accessibility_enabled 1 2>>"$LOGFILE"
  fi

  am start -n "$MAIN" --activity-clear-top >/dev/null 2>>"$LOGFILE"
  if [ "$HOME_AFTER_REPAIR" = "1" ]; then
    input keyevent 3 >/dev/null 2>>"$LOGFILE"
  fi
  echo "$(date '+%s')" > "$HEARTBEAT"
  log_line "repair requested a11y=$accessibility_enabled services=${enabled_services:-empty} appPid=$(app_pid)"
}

loop_forever() {
  echo "$$" > "$PIDFILE"
  echo "$VERSION" > "$VERSIONFILE"
  log_line "watchdog loop started pid=$$ interval=$INTERVAL"
  trap 'rm -f "$PIDFILE"; log_line "watchdog loop stopped"; exit 0' INT TERM EXIT
  while true; do
    accessibility_enabled="$(settings get secure accessibility_enabled 2>/dev/null)"
    enabled_services="$(settings get secure enabled_accessibility_services 2>/dev/null)"
    pid="$(app_pid)"
    if [ "$accessibility_enabled" != "1" ] || ! service_present "$enabled_services" || [ -z "$pid" ]; then
      repair_once
    else
      echo "$(date '+%s')" > "$HEARTBEAT"
    fi
    sleep "$INTERVAL"
  done
}

start_watchdog() {
  if is_watchdog_running; then
    echo "running pid=$(cat "$PIDFILE" 2>/dev/null) version=$VERSION"
    exit 0
  fi
  nohup sh "$0" run >/dev/null 2>&1 &
  echo "$!" > "$PIDFILE"
  echo "$VERSION" > "$VERSIONFILE"
  log_line "watchdog start requested pid=$!"
  echo "started pid=$! version=$VERSION"
}

stop_watchdog() {
  if is_watchdog_running; then
    pid="$(cat "$PIDFILE" 2>/dev/null)"
    kill "$pid" 2>/dev/null
    sleep 1
    if is_watchdog_pid "$pid"; then
      kill -9 "$pid" 2>/dev/null
    fi
    log_line "watchdog stop requested pid=$pid"
  elif [ -f "$PIDFILE" ]; then
    pid="$(cat "$PIDFILE" 2>/dev/null)"
    log_line "watchdog pidfile stale or foreign pid=$pid cmdline=$(pid_cmdline "$pid")"
  fi
  rm -f "$PIDFILE"
  echo "stopped version=$VERSION"
}

status_watchdog() {
  if is_watchdog_running; then
    running="yes"
    pid="$(cat "$PIDFILE" 2>/dev/null)"
  else
    running="no"
    pid=""
  fi
  echo "name=$NAME"
  echo "version=$VERSION"
  echo "running=$running"
  echo "pid=$pid"
  echo "appPid=$(app_pid)"
  echo "accessibility_enabled=$(settings get secure accessibility_enabled 2>/dev/null)"
  echo "enabled_accessibility_services=$(settings get secure enabled_accessibility_services 2>/dev/null)"
  echo "heartbeat=$(cat "$HEARTBEAT" 2>/dev/null)"
}

case "$1" in
  start|"")
    start_watchdog
    ;;
  stop)
    stop_watchdog
    ;;
  restart)
    stop_watchdog
    start_watchdog
    ;;
  status)
    status_watchdog
    ;;
  repair)
    repair_once
    ;;
  run)
    loop_forever
    ;;
  *)
    echo "usage: $0 {start|stop|restart|status|repair}"
    exit 2
    ;;
esac
