#!/usr/bin/env bash
# Swap only the test app JAR for one primary-fault experiment, with a timed local rollback.
set -euo pipefail
umask 077

action="${1:?deploy|restore|status required}"
candidate="${2:-/tmp/docgrid-ha420-candidate.jar}"
new_sha="${3:-}"
old_sha="${4:-}"
app_dir=/opt/docgrid
backup_dir=/opt/docgrid/ha-primary-fault-backup
timer=docgrid-ha-primary-fault-restore.timer

if (( EUID != 0 )) || [[ ! "$candidate" =~ ^/tmp/docgrid-ha[0-9]+-candidate[.]jar$ ]] ||
   [[ ! "$new_sha" =~ ^[0-9a-f]{64}$ ]] || [[ ! "$old_sha" =~ ^[0-9a-f]{64}$ ]]; then
  echo 'APP_FAULT_DEPLOY_INVALID_INPUT' >&2
  exit 2
fi

jar_sha() { sha256sum "$1" | cut -d ' ' -f 1; }
healthy() {
  for (( i=0; i<90; i++ )); do
    if [[ "$(curl --silent --max-time 2 --output /dev/null --write-out '%{http_code}' \
      http://127.0.0.1:8081/actuator/health || true)" == 200 ]]; then
      return 0
    fi
    sleep 2
  done
  return 1
}
timer_pending() {
  systemctl is-active --quiet "$timer" &&
    [[ "$(systemctl show "$timer" -p NextElapseUSecMonotonic --value)" != infinity ]]
}

case "$action" in
  deploy)
    # 1. Refuse stale backup state or a mismatched candidate before touching the live JAR.
    [[ ! -e "$backup_dir" ]] && [[ -f "$candidate" ]] &&
      [[ "$(jar_sha "$candidate")" == "$new_sha" ]] &&
      [[ "$(jar_sha "$app_dir/docgrid.jar")" == "$old_sha" ]] &&
      ! systemctl is-active --quiet "$timer" || {
        echo 'APP_FAULT_DEPLOY_GATE_FAILED' >&2; exit 1;
      }
    mkdir -m 0700 "$backup_dir"
    cp -p "$app_dir/docgrid.jar" "$backup_dir/docgrid.jar"
    [[ "$(jar_sha "$backup_dir/docgrid.jar")" == "$old_sha" ]]
    # 2. A VM-local timer restores the exact original JAR if the operator disconnects.
    systemd-run --quiet --collect --unit=docgrid-ha-primary-fault-restore \
      --on-active=80m /bin/bash -c \
      'cp -p /opt/docgrid/ha-primary-fault-backup/docgrid.jar /opt/docgrid/docgrid.jar && systemctl restart docgrid'
    timer_pending || { echo 'APP_FAULT_RESTORE_GUARD_MISSING' >&2; exit 1; }
    rollback() {
      trap - ERR
      cp -p "$backup_dir/docgrid.jar" "$app_dir/docgrid.jar"
      systemctl restart docgrid
      echo 'APP_FAULT_DEPLOY_ROLLED_BACK' >&2
    }
    trap rollback ERR
    # 3. Replace one app at a time and require its own health check before touching the other.
    install -o docgrid -g docgrid -m 0644 "$candidate" "$app_dir/docgrid.jar"
    systemctl restart docgrid
    healthy
    [[ "$(jar_sha "$app_dir/docgrid.jar")" == "$new_sha" ]]
    trap - ERR
    echo 'APP_FAULT_DEPLOYED health=200 restore_timer=armed'
    ;;
  restore)
    # 1. Keep the timer armed if the backup or expected hashes do not match.
    [[ -f "$backup_dir/docgrid.jar" ]] &&
      [[ "$(jar_sha "$backup_dir/docgrid.jar")" == "$old_sha" ]] || {
        echo 'APP_FAULT_RESTORE_BACKUP_INVALID' >&2; exit 1;
      }
    current="$(jar_sha "$app_dir/docgrid.jar")"
    [[ "$current" == "$new_sha" || "$current" == "$old_sha" ]] || {
      echo 'APP_FAULT_RESTORE_CURRENT_UNKNOWN' >&2; exit 1;
    }
    # 2. Verify the old JAR is serving before disarming the independent rollback timer.
    cp -p "$backup_dir/docgrid.jar" "$app_dir/docgrid.jar"
    systemctl restart docgrid
    healthy
    [[ "$(jar_sha "$app_dir/docgrid.jar")" == "$old_sha" ]]
    if systemctl is-active --quiet "$timer"; then systemctl stop "$timer"; fi
    echo 'APP_FAULT_RESTORED health=200 restore_timer=stopped'
    ;;
  status)
    live="$(jar_sha "$app_dir/docgrid.jar")"
    if [[ "$live" == "$new_sha" ]]; then state=new; elif [[ "$live" == "$old_sha" ]]; then state=old;
    else state=unknown; fi
    if timer_pending; then armed=true; else armed=false; fi
    echo "APP_FAULT_STATUS jar=$state restore_timer=$armed"
    ;;
  *)
    echo 'APP_FAULT_ACTION_INVALID' >&2
    exit 2
    ;;
esac
