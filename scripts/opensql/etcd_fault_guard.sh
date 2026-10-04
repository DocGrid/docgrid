#!/usr/bin/env bash
# Bound a containerized etcd pause with a host-side timer independent of the client session.
set -euo pipefail

action="${1:?action required: status|arm|pause|recover|cancel}"
container="${2:?container required}"
run_id="${3:?run ID required}"
guard_seconds="${4:-90}"

if (( EUID != 0 )) || [[ ! "$container" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] ||
   [[ ! "$run_id" =~ ^[a-z0-9][a-z0-9-]{0,23}$ ]] ||
   [[ ! "$guard_seconds" =~ ^[0-9]+$ ]] ||
   (( guard_seconds < 30 || guard_seconds > 180 )); then
  echo 'ETCD_GUARD_INVALID_INPUT' >&2
  exit 2
fi

unit="docgrid-etcd-resume-${run_id}"
timer="${unit}.timer"

etcd_process() {
  /usr/bin/docker exec "$container" sh -c \
    "ps -eo pid=,stat=,comm= | awk '\$3 == \"etcd\" && \$2 !~ /^Z/ { print \$1, \$2 }'"
}

single_process() {
  local processes
  processes="$(etcd_process)"
  [[ -n "$processes" && "$(printf '%s\n' "$processes" | wc -l | tr -d ' ')" == 1 ]] || {
    echo 'ETCD_GUARD_PROCESS_COUNT_UNEXPECTED' >&2
    return 1
  }
  printf '%s\n' "$processes"
}

timer_pending() {
  systemctl is-active --quiet "$timer" &&
    [[ "$(systemctl show "$timer" -p NextElapseUSecMonotonic --value)" != 'infinity' ]]
}

case "$action" in
  status)
    process="$(single_process)"
    if [[ "${process#* }" == *T* ]]; then state=paused; else state=running; fi
    if timer_pending; then guard=armed; else guard=absent; fi
    printf 'etcd_process=%s recovery_guard=%s\n' "$state" "$guard"
    ;;
  arm)
    # 1. A live, unpaused etcd process and an independent recovery timer are prerequisites.
    process="$(single_process)"
    [[ "${process#* }" != *T* ]] && ! timer_pending || {
      echo 'ETCD_GUARD_ARM_PRECONDITION_FAILED' >&2; exit 1;
    }
    systemd-run --quiet --collect --unit="$unit" --on-active="${guard_seconds}s" \
      --on-unit-active=20s --timer-property=AccuracySec=1s \
      /usr/local/sbin/docgrid-etcd-fault-guard recover "$container" "$run_id" >/dev/null
    timer_pending || { echo 'ETCD_GUARD_TIMER_NOT_PENDING' >&2; exit 1; }
    echo 'recovery_guard=armed'
    ;;
  pause)
    # 2. Pause only the unique etcd PID, never Patroni, PostgreSQL, or the container.
    timer_pending || { echo 'ETCD_GUARD_NOT_ARMED' >&2; exit 1; }
    process="$(single_process)"
    [[ "${process#* }" != *T* ]] || { echo 'ETCD_ALREADY_PAUSED' >&2; exit 1; }
    /usr/bin/docker exec "$container" sh -c 'kill -STOP "$1"' sh "${process%% *}"
    [[ "$(single_process)" == *T* ]] || { echo 'ETCD_PAUSE_NOT_CONFIRMED' >&2; exit 1; }
    printf 'etcd_paused_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)"
    ;;
  recover)
    # 3. The timer calls this idempotent action even if the operator disconnects.
    process="$(single_process)"
    /usr/bin/docker exec "$container" sh -c 'kill -CONT "$1"' sh "${process%% *}"
    for (( attempt=1; attempt<=10; attempt++ )); do
      if [[ "$(single_process)" != *T* ]]; then
        printf 'etcd_resumed_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)"
        echo 'etcd_process=running'
        exit 0
      fi
      sleep 1
    done
    echo 'ETCD_GUARD_RECOVERY_NOT_CONFIRMED' >&2
    exit 1
    ;;
  cancel)
    # 4. Never cancel the timer while its etcd process remains paused.
    process="$(single_process)"
    [[ "${process#* }" != *T* ]] || { echo 'ETCD_GUARD_CANCEL_UNSAFE' >&2; exit 1; }
    if systemctl is-active --quiet "$timer"; then systemctl stop "$timer"; fi
    echo 'recovery_guard=cancelled'
    ;;
  *)
    echo 'ETCD_GUARD_UNKNOWN_ACTION' >&2
    exit 2
    ;;
esac
