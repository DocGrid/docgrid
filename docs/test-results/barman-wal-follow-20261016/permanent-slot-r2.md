# DR-BARMAN-PERMANENT-SLOT-20261016-R2 — Patroni 최상위 영구 슬롯 등록

- 시각: 2026-10-16 22:06:29~22:06:47 KST.
- 목적·위치: GCP Patroni DCS 공유 설정에 기존 Barman 물리 슬롯을 올바른 최상위 `slots` 키로 등록.
- 방법: 현재 리더·4GB WAL 보존 한도와 기존 설정을 재확인하고, 비공개 원본을 0600으로 보관했다. 로컬 Patroni `PATCH /config`에서 잘못 둔 중첩 키를 제거하고 최상위 `slots`에 `type=physical`을 등록했다. 슬롯 이름·내부 주소는 기록하지 않았다.
- 통과 기준: PATCH 수락, 4GB 보존 한도 유지, 후속 조회에서 세 노드 슬롯 확인.
- 관측: PATCH 수락, 한도 유지, 원본 백업 생성. 임시 OS Login 키 제거 명령 성공.
- 판정: **설정 적용 통과**. 실제 슬롯 3/3은 [후속 조회](slot-topology-r7.md)에서 확인했다. [Patroni 문서](https://patroni.readthedocs.io/en/latest/dynamic_configuration.html#slots)의 최상위 키에 맞췄다.
