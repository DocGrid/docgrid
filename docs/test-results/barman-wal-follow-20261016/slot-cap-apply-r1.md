# DR-BARMAN-SLOT-CAP-APPLY-20261016-R1 — 클러스터 슬롯 WAL 보존 한도 적용

- 시작·종료: 2026-10-16 22:00:57~22:01:06 KST.
- 목적·위치: GCP Patroni 리더의 DCS 공유 설정에 슬롯 WAL 보존 한도 4GB 적용.
- 방법: 리더 역할과 기존 공유 설정에 해당 값이 없음을 확인하고, 변경 전 설정을 호스트의 비공개 0600 파일에 보관했다. 로컬 Patroni `PATCH /config`로 `postgresql.parameters.max_slot_wal_keep_size=4GB`만 추가했다. 설정 원문·내부 주소 미기록.
- 통과 기준: HTTP 200/202, 응답 설정 4GB, 비공개 백업 생성, 임시 OS Login 키 제거. 실제 DB 노드별 값은 후속 Run으로 확인.
- 관측: HTTP 200, 응답 설정 4GB, 백업 생성, 임시 키 제거 명령 성공.
- 판정: **설정 적용 통과**. 이 변경은 사용자가 범위를 명시해 승인한 뒤 실행했다. 실제 세 노드 반영은 [별도 조회](slot-topology-r3.md)로 확인했다.
