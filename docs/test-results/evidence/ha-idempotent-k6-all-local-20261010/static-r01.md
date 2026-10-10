# 정적 검사 r01 — 셸 종료 블록 구문 오류

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-all-static-r01`, 2026-10-10 17:35 KST |
| 위치·명령 | 로컬 별도 작업 공간; `bash -n scripts/opensql/run_ha_probe_replay_all_k6.sh` |
| 기준·결과 | 종료 코드 0 목표. **구문 오류 1개**, 종료 코드 2 |
| 해석·조치 | here-document 뒤 오류 처리 블록 위치가 잘못됐다. `if ! python3 ...; then ... fi` 형태로 수정 |
