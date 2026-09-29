#!/usr/bin/env bash
# Run as root on a standby VM host; Patroni owns this recovery parameter.
set -euo pipefail

action="${1:?action required}"
container="${2:?container required}"
run_id="${3:?run ID required}"
duration="${4:-180}"
delay_seconds="${5:-90}"
if [[ ! "$container" =~ ^docgrid-node[23]$ ]] || (( EUID != 0 )) ||
   [[ ! "$run_id" =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] ||
   [[ ! "$duration" =~ ^[0-9]+$ ]] || (( duration < 30 || duration > 300 )) ||
   [[ ! "$delay_seconds" =~ ^[0-9]+$ ]] || (( delay_seconds < 1 || delay_seconds > 180 )); then
  echo 'Invalid standby guard target or duration.' >&2
  exit 2
fi

unit="docgrid-delay-reset-${run_id}-${container}"
timer="${unit}.timer"
mount_source="$(/usr/bin/docker inspect "$container" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/docgrid"}}{{.Source}}{{end}}{{end}}')"
if [[ "$mount_source" != /* ]] || [[ "$(basename "$mount_source")" != "$container" ]]; then
  echo 'The expected dedicated container volume is missing.' >&2
  exit 1
fi
config="${mount_source}/opensql/etc/patroni/patroni.yml"
backup_dir='/run/docgrid-permission-delay'
backup="${backup_dir}/${container}-${run_id}.yml"
psql_command='. /var/lib/docgrid/opensql/etc/credentials.env; export PGPASSWORD="${PG_SUPERUSER_PASSWORD}"; exec /var/lib/docgrid/opensql/bin/psql -h 127.0.0.1 -U postgres -d postgres -X -v ON_ERROR_STOP=1 -At -F , -c "$1"'

query() {
  # 1. Keep the root-only DB password inside the existing container.
  /usr/bin/docker exec "$container" sh -c "$psql_command" sh "$1"
}
setting() { query "SELECT setting, source FROM pg_settings WHERE name = 'recovery_min_apply_delay'"; }
is_standby() { [[ "$(query 'SELECT pg_is_in_recovery()')" == 't' ]]; }
is_streaming() {
  [[ "$(query "SELECT COALESCE((SELECT status FROM pg_stat_wal_receiver LIMIT 1), 'none')")" == 'streaming' ]]
}
timer_pending() {
  systemctl is-active --quiet "$timer" &&
    [[ "$(systemctl show "$timer" -p NextElapseUSecMonotonic --value)" != 'infinity' ]]
}
reload_patroni() {
  /usr/bin/docker exec "$container" curl --silent --show-error --fail --max-time 5 \
    --output /dev/null --request POST http://127.0.0.1:8008/reload
}

edit_config() {
  # 2. Insert/remove only a local recovery_conf block; preserve exact original bytes.
  python3 - "$1" "$config" "$backup" "$delay_seconds" <<'PY'
import os
import shutil
import sys
import tempfile

action, config, backup, seconds = sys.argv[1:]
original = open(backup, 'rb').read()
current = open(config, 'rb').read()
lines = original.splitlines(keepends=True)
start = [i for i, line in enumerate(lines) if line == b'postgresql:\n']
if len(start) != 1:
    raise SystemExit('Expected exactly one top-level postgresql block')
end = next((i for i in range(start[0] + 1, len(lines))
            if lines[i] and lines[i][:1] not in (b' ', b'\t', b'\n', b'#')),
           len(lines))
if any(line.lstrip().startswith(b'recovery_conf:') for line in lines[start[0] + 1:end]):
    raise SystemExit('Pre-existing recovery_conf must not be overwritten')
if b'recovery_min_apply_delay' in original:
    raise SystemExit('Pre-existing apply delay must not be overwritten')
changed = b''.join(lines[:end]) + (f'  recovery_conf:\n    recovery_min_apply_delay: {seconds}s\n').encode() + b''.join(lines[end:])
expected, replacement = (original, changed) if action == 'apply' else (changed, original)
if current == replacement:
    raise SystemExit(0)
if current != expected:
    raise SystemExit('Patroni config changed independently; refusing to overwrite it')
metadata = os.stat(config)
fd, temp = tempfile.mkstemp(prefix='.docgrid-delay-', dir=os.path.dirname(config))
try:
    with os.fdopen(fd, 'wb') as stream:
        stream.write(replacement)
        stream.flush()
        os.fsync(stream.fileno())
    os.chown(temp, metadata.st_uid, metadata.st_gid)
    shutil.copystat(config, temp)
    os.replace(temp, config)
finally:
    if os.path.exists(temp):
        os.unlink(temp)
PY
}

restore_once() {
  if [[ ! -f "$backup" ]]; then
    [[ "$(setting)" == '0,default' ]]
    return
  fi
  edit_config restore || return 1
  reload_patroni || return 1
  for (( check = 0; check < 10; check++ )); do
    [[ "$(setting)" == '0,default' ]] && return 0
    sleep 1
  done
  return 1
}
restore() {
  # 3. The VM-host timer retries even if the client process or SSH disappears.
  for (( attempt = 0; attempt < 12; attempt++ )); do
    if restore_once; then
      echo 'delay-reset'
      return 0
    fi
    sleep 5
  done
  echo 'Patroni delay reset failed; manual cluster inspection required.' >&2
  return 1
}

case "$action" in
  arm)
    command -v systemd-run >/dev/null
    if ! is_standby || ! is_streaming || [[ "$(setting)" != '0,default' ]] ||
       timer_pending || [[ ! -f "$config" ]] || [[ -L "$config" ]]; then
      echo 'Standby baseline, Patroni config, or timer precondition failed.' >&2
      exit 1
    fi
    install -d -m 0700 "$backup_dir"
    [[ ! -e "$backup" ]] || { echo 'Guard backup already exists.' >&2; exit 1; }
    cp --preserve=all "$config" "$backup"
    chmod 0600 "$backup"
    systemd-run --quiet --collect --unit="$unit" --on-active="${duration}s" \
      /usr/local/sbin/docgrid-standby-apply-delay-guard restore "$container" "$run_id" \
      "$duration" "$delay_seconds" >/dev/null
    timer_pending || { echo 'Independent reset timer did not become pending.' >&2; exit 1; }
    echo 'armed'
    ;;
  apply)
    timer_pending && [[ -f "$backup" ]] ||
      { echo 'Independent timer or backup missing.' >&2; exit 1; }
    if ! is_standby || ! is_streaming || [[ "$(setting)" != '0,default' ]]; then
      echo 'Standby baseline changed before apply.' >&2
      exit 1
    fi
    free_kb="$(/usr/bin/docker exec "$container" df -Pk /var/lib/docgrid/opensql/data/pgsql | awk 'NR == 2 {print $4}')"
    if [[ ! "$free_kb" =~ ^[0-9]+$ ]] || (( free_kb < 1048576 )); then
      echo 'Less than 1 GiB free on standby data filesystem.' >&2
      exit 1
    fi
    if ! edit_config apply || ! reload_patroni; then
      restore || true
      echo 'Could not apply Patroni-managed standby delay.' >&2
      exit 1
    fi
    for (( check = 0; check < 15; check++ )); do
      if [[ "$(setting)" == "$(( delay_seconds * 1000 )),configuration file" ]]; then
        timer_pending && echo 'delay-applied' && exit 0
        break
      fi
      sleep 1
    done
    restore || true
    echo 'Effective delay or independent reset timer was not verified.' >&2
    exit 1
    ;;
  restore)
    restore
    ;;
  cancel)
    if ! is_standby || ! is_streaming || [[ "$(setting)" != '0,default' ]]; then
      echo 'Do not cancel until default delay and streaming return.' >&2
      exit 1
    fi
    if systemctl is-active --quiet "$timer"; then systemctl stop "$timer"; fi
    if [[ -f "$backup" ]] && cmp --silent "$config" "$backup"; then rm -- "$backup"; fi
    echo 'cancelled'
    ;;
  status)
    if timer_pending; then armed='true'; else armed='false'; fi
    printf 'standby=%s streaming=%s armed=%s delay=%s backlog_bytes=%s free_kb=%s\n' \
      "$(is_standby && echo true || echo false)" \
      "$(is_streaming && echo true || echo false)" "$armed" "$(setting)" \
      "$(query 'SELECT COALESCE(pg_wal_lsn_diff(pg_last_wal_receive_lsn(), pg_last_wal_replay_lsn()), 0)::bigint')" \
      "$(/usr/bin/docker exec "$container" df -Pk /var/lib/docgrid/opensql/data/pgsql | awk 'NR == 2 {print $4}')"
    ;;
  *)
    echo 'Unknown guard action.' >&2
    exit 2
    ;;
esac
