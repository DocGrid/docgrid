#!/usr/bin/env bash
# Bound a single OpenProxy fault with a host-side recovery timer; never touch the DB process.
set -euo pipefail

action="${1:?action required: status|arm|stop|recover|cancel}"
container="${2:?container required}"
run_id="${3:?run ID required}"
guard_seconds="${4:-100}"

if (( EUID != 0 )) || [[ ! "$container" =~ ^docgrid-node[23]$ ]] ||
   [[ ! "$run_id" =~ ^[a-z0-9][a-z0-9-]{0,23}$ ]] ||
   [[ ! "$guard_seconds" =~ ^[0-9]+$ ]] ||
   (( guard_seconds < 30 || guard_seconds > 180 )); then
  echo 'PROXY_GUARD_INVALID_INPUT' >&2
  exit 2
fi

unit="docgrid-proxy-recover-${run_id}"
timer="${unit}.timer"
binary=/var/lib/docgrid/opensql/bin/openproxy
config=/var/lib/docgrid/opensql/etc/openproxy/openproxy.toml

live_pids() {
  /usr/bin/docker exec "$container" sh -c \
    "ps -eo pid=,stat=,comm= | awk '\$3 == \"openproxy\" && \$2 !~ /^Z/ { print \$1 }'"
}

ready() {
  /usr/bin/docker exec "$container" /var/lib/docgrid/opensql/bin/pg_isready \
    -q -h 127.0.0.1 -p 6432 -t 2 >/dev/null 2>&1
}

timer_pending() {
  systemctl is-active --quiet "$timer" &&
    [[ "$(systemctl show "$timer" -p NextElapseUSecMonotonic --value)" != 'infinity' ]]
}

case "$action" in
  status)
    pids="$(live_pids)"
    if [[ -n "$pids" ]]; then count="$(printf '%s\n' "$pids" | wc -l | tr -d ' ')"; else count=0; fi
    if ready; then port=ready; else port=down; fi
    if timer_pending; then guard=armed; else guard=absent; fi
    printf 'live_proxy_processes=%s port=%s recovery_guard=%s\n' "$count" "$port" "$guard"
    ;;
  arm)
    # 1. Arm independent recovery before allowing a destructive signal.
    [[ "$(live_pids | wc -l | tr -d ' ')" == 1 ]] && ready && ! timer_pending || {
      echo 'PROXY_GUARD_ARM_PRECONDITION_FAILED' >&2; exit 1;
    }
    # AccuracySec defaults to a wide timer window; recovery must fire near its deadline.
    systemd-run --quiet --collect --unit="$unit" --on-active="${guard_seconds}s" \
      --on-unit-active=30s --timer-property=AccuracySec=1s \
      /usr/local/sbin/docgrid-openproxy-fault recover "$container" "$run_id" >/dev/null
    timer_pending || { echo 'PROXY_GUARD_TIMER_NOT_PENDING' >&2; exit 1; }
    echo 'recovery_guard=armed'
    ;;
  stop)
    # 2. Only one live proxy process in the chosen container may be stopped.
    timer_pending || { echo 'PROXY_GUARD_NOT_ARMED' >&2; exit 1; }
    pids="$(live_pids)"
    [[ -n "$pids" && "$(printf '%s\n' "$pids" | wc -l | tr -d ' ')" == 1 ]] || {
      echo 'PROXY_GUARD_PROCESS_COUNT_UNEXPECTED' >&2; exit 1;
    }
    /usr/bin/docker exec "$container" sh -c 'kill -KILL "$1"' sh "$pids"
    for (( attempt=1; attempt<=10; attempt++ )); do
      if [[ -z "$(live_pids)" ]] && ! ready; then echo 'proxy_fault=active'; exit 0; fi
      sleep 1
    done
    echo 'PROXY_GUARD_STOP_NOT_CONFIRMED' >&2
    exit 1
    ;;
  recover)
    # 3. The systemd timer retries this idempotent action if the client disappears.
    pids="$(live_pids)"
    if [[ -z "$pids" ]]; then
      # OpenProxy resolves writable runtime files relative to its installation directory.
      /usr/bin/docker exec -d -u opensql -w /var/lib/docgrid/opensql \
        "$container" "$binary" "$config" >/dev/null
    elif [[ "$(printf '%s\n' "$pids" | wc -l | tr -d ' ')" != 1 ]]; then
      echo 'PROXY_GUARD_MULTIPLE_LIVE_PROCESSES' >&2
      exit 1
    fi
    for (( attempt=1; attempt<=20; attempt++ )); do
      if [[ "$(live_pids | wc -l | tr -d ' ')" == 1 ]] && ready; then
        echo 'proxy_recovery=ready'
        exit 0
      fi
      sleep 2
    done
    echo 'PROXY_GUARD_RECOVERY_NOT_READY' >&2
    exit 1
    ;;
  cancel)
    # 4. Never cancel the independent guard while its proxy remains down.
    [[ "$(live_pids | wc -l | tr -d ' ')" == 1 ]] && ready || {
      echo 'PROXY_GUARD_CANCEL_UNSAFE' >&2; exit 1;
    }
    if systemctl is-active --quiet "$timer"; then systemctl stop "$timer"; fi
    echo 'recovery_guard=cancelled'
    ;;
  *)
    echo 'PROXY_GUARD_UNKNOWN_ACTION' >&2
    exit 2
    ;;
esac
