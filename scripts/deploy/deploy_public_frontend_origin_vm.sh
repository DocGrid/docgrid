#!/usr/bin/env bash
# Replace one existing DocGrid app JAR for the public frontend, with an on-host rollback copy.
set -euo pipefail

candidate="${1:?candidate JAR required}"
expected_old_sha="${2:?old SHA-256 required}"
expected_new_sha="${3:?new SHA-256 required}"
app_dir=/opt/docgrid
live_jar="$app_dir/docgrid.jar"
backup_jar="$app_dir/docgrid.jar.backup-vercel-origin-20261010"
restore_timer=docgrid-vercel-origin-rollback.timer

jar_sha() { sha256sum "$1" | cut -d ' ' -f 1; }
healthy() {
  for (( attempt=1; attempt<=60; attempt++ )); do
    if [[ "$(curl --silent --max-time 2 --output /dev/null --write-out '%{http_code}' \
      http://127.0.0.1:8081/actuator/health || true)" == 200 ]]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# 1. Refuse an unexpected JAR or an existing backup before touching the live service.
if (( EUID != 0 )) || [[ ! "$candidate" =~ ^/tmp/docgrid-vercel-origin-20261010[.]jar$ ]] ||
   [[ ! "$expected_old_sha" =~ ^[0-9a-f]{64}$ ]] ||
   [[ ! "$expected_new_sha" =~ ^[0-9a-f]{64}$ ]] ||
   [[ ! -f "$candidate" ]] || [[ ! -f "$live_jar" ]] || [[ -e "$backup_jar" ]] ||
   systemctl is-active --quiet "$restore_timer" ||
   [[ "$(jar_sha "$live_jar")" != "$expected_old_sha" ]] ||
   [[ "$(jar_sha "$candidate")" != "$expected_new_sha" ]] ||
   ! systemctl is-active --quiet docgrid; then
  echo 'DEPLOY_GATE_FAILED' >&2
  exit 2
fi

# 2. Preserve the original bytes and roll back automatically if restart or health fails.
cp -p "$live_jar" "$backup_jar"
[[ "$(jar_sha "$backup_jar")" == "$expected_old_sha" ]]
# The VM-local timer can restore service even if this SSH session disappears mid-deploy.
systemd-run --quiet --collect --unit=docgrid-vercel-origin-rollback \
  --on-active=30m /bin/sh -c \
  'cp -p /opt/docgrid/docgrid.jar.backup-vercel-origin-20261010 /opt/docgrid/docgrid.jar && systemctl restart docgrid'
systemctl is-active --quiet "$restore_timer"
rollback() {
  trap - ERR
  cp -p "$backup_jar" "$live_jar"
  systemctl restart docgrid
  systemctl stop "$restore_timer" || true
  echo 'DEPLOY_ROLLED_BACK' >&2
}
trap rollback ERR

# 3. Replace only this VM, then require its management health before the next VM is touched.
install -o "$(stat -c %U "$live_jar")" -g "$(stat -c %G "$live_jar")" \
  -m 0644 "$candidate" "$live_jar"
systemctl restart docgrid
healthy
[[ "$(jar_sha "$live_jar")" == "$expected_new_sha" ]]
trap - ERR
echo 'DEPLOY_APPLIED health=200 backup=present restore_timer=armed'
