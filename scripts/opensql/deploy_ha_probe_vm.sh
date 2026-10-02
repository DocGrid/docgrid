#!/usr/bin/env bash
# Enable the HA probe on one existing test app VM while keeping a rollback copy.
set -euo pipefail

candidate="${1:?candidate JAR path required}"
expected_sha="${2:?expected SHA-256 required}"
service_dir=/opt/docgrid
backup_dir=/opt/docgrid/ha-probe-backup
unit_file=/etc/systemd/system/docgrid.service

if (( EUID != 0 )) || [[ ! "$expected_sha" =~ ^[0-9a-f]{64}$ ]] ||
   [[ ! -f "$candidate" ]] || [[ -e "$backup_dir" ]]; then
  echo '배포 선행 조건 실패: root·JAR·SHA·백업 위치를 확인하세요' >&2
  exit 2
fi
if [[ "$(sha256sum "$candidate" | cut -d ' ' -f 1)" != "$expected_sha" ]] ||
   [[ "$(grep -c '^SPRING_PROFILES_ACTIVE=opensql-ha$' "$service_dir/.env")" != 1 ]] ||
   [[ "$(grep -c '^Environment=SPRING_PROFILES_ACTIVE=opensql-ha$' "$unit_file")" != 1 ]] ||
   grep -q '^docgrid.ha-probe.enabled=' "$service_dir/.env"; then
  echo '기존 프로필 또는 전송된 JAR가 예상과 다릅니다' >&2
  exit 2
fi

mkdir -m 0700 "$backup_dir"
cp -p "$service_dir/docgrid.jar" "$backup_dir/docgrid.jar"
cp -p "$service_dir/.env" "$backup_dir/.env"
cp -p "$unit_file" "$backup_dir/docgrid.service"

rollback() {
  trap - ERR
  echo '헬스체크 실패: 기존 JAR·설정을 복원합니다' >&2
  cp -p "$backup_dir/docgrid.jar" "$service_dir/docgrid.jar"
  cp -p "$backup_dir/.env" "$service_dir/.env"
  cp -p "$backup_dir/docgrid.service" "$unit_file"
  systemctl daemon-reload
  systemctl restart docgrid
}
trap rollback ERR

install -o docgrid -g docgrid -m 0644 "$candidate" "$service_dir/docgrid.jar"
sed -i 's/^SPRING_PROFILES_ACTIVE=opensql-ha$/SPRING_PROFILES_ACTIVE=opensql-ha,ha-probe/' "$service_dir/.env"
sed -i '$a docgrid.ha-probe.enabled=true' "$service_dir/.env"
sed -i 's/^Environment=SPRING_PROFILES_ACTIVE=opensql-ha$/Environment=SPRING_PROFILES_ACTIVE=opensql-ha,ha-probe/' "$unit_file"
chown docgrid:docgrid "$service_dir/.env"
chmod 0600 "$service_dir/.env"
systemctl daemon-reload
systemctl restart docgrid

healthy=false
for (( attempt=1; attempt<=90; attempt++ )); do
  if [[ "$(curl --silent --max-time 2 --output /dev/null --write-out '%{http_code}' \
      http://127.0.0.1:8081/actuator/health || true)" == 200 ]]; then
    healthy=true
    break
  fi
  sleep 2
done
if [[ "$healthy" != true ]]; then
  false
fi
trap - ERR
echo '앱 헬스체크=200; 기존 JAR·설정 백업 유지; probe 활성'
