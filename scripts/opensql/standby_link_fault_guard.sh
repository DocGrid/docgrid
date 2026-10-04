#!/usr/bin/env bash
# Bound one synchronous standby's PostgreSQL replication-link loss with a host timer.
set -euo pipefail

action="${1:?action required: status|arm|inject|recover|cancel}"
container="${2:?local primary container required}"
run_id="${3:?run ID required}"
guard_seconds="${4:-150}"

if (( EUID != 0 )) || [[ ! "$container" =~ ^docgrid-node[123]$ ]] ||
   [[ ! "$run_id" =~ ^[a-z0-9][a-z0-9-]{0,23}$ ]] ||
   [[ ! "$guard_seconds" =~ ^[0-9]+$ ]] ||
   (( guard_seconds < 60 || guard_seconds > 240 )); then
  echo 'STANDBY_LINK_GUARD_INVALID_INPUT' >&2
  exit 2
fi

unit="docgrid-standby-link-recover-${run_id}"
timer="${unit}.timer"
state="/run/docgrid-standby-link-${run_id}.target"
tag="docgrid-standby-${run_id}"

query() {
  /usr/bin/docker exec -u opensql "$container" \
    /var/lib/docgrid/opensql/bin/psql -w -X -v ON_ERROR_STOP=1 \
    -h /var/lib/docgrid/opensql/tmp -U postgres -d postgres -tA -F '|' -c "$1"
}

timer_pending() {
  systemctl is-active --quiet "$timer" &&
    [[ "$(systemctl show "$timer" -p NextElapseUSecMonotonic --value)" != 'infinity' ]]
}

target() {
  [[ -f "$state" && "$(stat -c '%a' "$state")" == 600 ]] || {
    echo 'STANDBY_LINK_TARGET_MISSING' >&2; return 1;
  }
  local ip port
  IFS='|' read -r ip port < "$state"
  [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ && "$port" == 5432 ]] || {
    echo 'STANDBY_LINK_TARGET_INVALID' >&2; return 1;
  }
  printf '%s|%s\n' "$ip" "$port"
}

rule() {
  local ip port
  IFS='|' read -r ip port < <(target)
  /usr/sbin/iptables -w 5 "$1" INPUT \
    -s "$ip" -p tcp --dport "$port" \
    -m comment --comment "$tag" -j REJECT --reject-with tcp-reset
}

rule_exists() { rule -C >/dev/null 2>&1; }

sync_target() {
  # 1. Fail closed unless exactly one primary, two streaming links and one sync link exist.
  [[ "$(query 'SELECT pg_is_in_recovery()')" == f ]] || {
    echo 'STANDBY_LINK_NOT_PRIMARY' >&2; return 1;
  }
  [[ -n "$(query "SELECT current_setting('synchronous_standby_names')")" ]] || {
    echo 'STANDBY_LINK_NOT_SYNCHRONOUS' >&2; return 1;
  }
  local counts
  counts="$(query "SELECT count(*) FILTER (WHERE state='streaming'),
    count(*) FILTER (WHERE state='streaming' AND sync_state='sync'),
    count(*) FILTER (WHERE state='streaming' AND sync_state='async')
    FROM pg_stat_replication")"
  [[ "$counts" == '2|1|1' ]] || {
    echo 'STANDBY_LINK_REPLICATION_UNEXPECTED' >&2; return 1;
  }
  query "SELECT host(client_addr),current_setting('port')
    FROM pg_stat_replication WHERE state='streaming' AND sync_state='sync'"
}

case "$action" in
  status)
    if timer_pending; then guard=armed; else guard=absent; fi
    if [[ -f "$state" ]] && rule_exists; then link=blocked; else link=normal; fi
    printf 'recovery_guard=%s replication_link=%s\n' "$guard" "$link"
    ;;
  arm)
    # 2. Save only the current sync standby address before arming an independent timer.
    [[ ! -e "$state" ]] && ! timer_pending || {
      echo 'STANDBY_LINK_ALREADY_ARMED' >&2; exit 1;
    }
    /usr/sbin/iptables -w 5 -S INPUT >/dev/null
    selected="$(sync_target)"
    [[ "$selected" == *'|5432' && "${selected#*|}" == 5432 ]] || {
      echo 'STANDBY_LINK_TARGET_UNEXPECTED' >&2; exit 1;
    }
    ( umask 077; printf '%s\n' "$selected" > "$state" )
    rule_exists && { echo 'STANDBY_LINK_RULE_EXISTS' >&2; exit 1; }
    systemd-run --quiet --collect --unit="$unit" --on-active="${guard_seconds}s" \
      --on-unit-active=30s --timer-property=AccuracySec=1s \
      /usr/local/sbin/docgrid-standby-link-fault recover "$container" "$run_id" >/dev/null || {
        rm -- "$state"
        echo 'STANDBY_LINK_TIMER_CREATION_FAILED' >&2
        exit 1
      }
    timer_pending || { echo 'STANDBY_LINK_TIMER_NOT_PENDING' >&2; exit 1; }
    echo 'recovery_guard=armed'
    ;;
  inject)
    # 3. Reject only the selected standby's DB packets; Patroni, etcd and proxies remain up.
    timer_pending && ! rule_exists && [[ "$(sync_target)" == "$(target)" ]] || {
      echo 'STANDBY_LINK_INJECT_PRECONDITION_FAILED' >&2; exit 1;
    }
    rule -I
    rule_exists || { echo 'STANDBY_LINK_INJECT_NOT_CONFIRMED' >&2; exit 1; }
    printf 'replication_link_blocked_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)"
    ;;
  recover)
    # 4. The host timer removes the exact tagged rule even if the client disappears.
    if rule_exists; then rule -D; fi
    rule_exists && { echo 'STANDBY_LINK_RECOVERY_NOT_CONFIRMED' >&2; exit 1; }
    printf 'replication_link_recovered_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)"
    ;;
  cancel)
    # 5. Never cancel while the injected rule remains active.
    rule_exists && { echo 'STANDBY_LINK_CANCEL_UNSAFE' >&2; exit 1; }
    if systemctl is-active --quiet "$timer"; then systemctl stop "$timer"; fi
    if [[ -f "$state" ]]; then rm -- "$state"; fi
    echo 'recovery_guard=cancelled'
    ;;
  *)
    echo 'STANDBY_LINK_UNKNOWN_ACTION' >&2
    exit 2
    ;;
esac
