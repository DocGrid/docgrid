# runner 초기 VU 범위 검증 — ha412-runner-local-02

- 목적: 유효한 0600 토큰 파일 조건에서 최대 800을 넘는 초기 VU 900만 거부되는지 확인.
- 위치: 로컬 개발 머신. 방법: `mktemp`의 0600 빈 시험 파일 생성 → `bash scripts/opensql/run_ha_probe_k6.sh ha412invalid 70 1s <loopback probe URL> <0600 시험 파일> primary-switchover 900` → 정확한 시험 파일 삭제.
- 성공 기준: k6를 실행하거나 실행 디렉터리를 만들기 전 runner 종료 코드 2, 임시 파일 제거.
- 결과: **종료 코드 2**, 토큰 권한 정상, 임시 파일 제거 성공. 실제 유효한 280·400 값은 GCP 분리 실행에서 runner 배치 SHA 일치와 함께 검증했다.
