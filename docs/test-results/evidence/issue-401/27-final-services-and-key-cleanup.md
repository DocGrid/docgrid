# #401 최종 서비스·임시 접근 정리

- 실행 ID: `issue-401-final-cleanup-01`
- 목적: DB 재합류 이후 양쪽 프록시 프로세스를 확인하고, 시험용 만료형 SSH 키를 3 VM에서 제거한다.
- 실행 위치: GCP DB VM 3대, 인스턴스 메타데이터, 개발자 로컬 임시 키 디렉터리.
- 절차: OpenProxy 실행 수·Patroni/etcd 확인 → 각 VM의 정확한 임시 SSH 키 메타데이터 항목만 제거 → 세 VM에서 잔존 0 확인 → 로컬 임시 개인키·공개키 삭제.
- 성공 기준: OpenProxy A/B 실행, leader 1·streaming replica 2·etcd 3/3, 시험용 인스턴스 SSH 키 0/3, 로컬 임시 키 0개. 기존 프로젝트 공통 SSH 키·방화벽 정책은 유지한다.
- 결과: OpenProxy 프로세스 node2/node3 각각 **1개**. 최종 Patroni leader **1**·streaming replica **2**, etcd healthy **3/3**. 세 VM 인스턴스 메타데이터에서 이번 임시 키가 각각 **단독 1줄**임을 정확히 일치 확인 후 제거했고, 최종 인스턴스 전용 `ssh-keys` 항목 **0/3**. 로컬 임시 개인키·공개키 정확히 **2개**와 빈 임시 디렉터리 삭제 확인.
- 판정: 임시 접근 정리 **PASS**. 프로젝트 공통 SSH 키·OS Login·방화벽 정책은 변경하지 않았다. 세 신규 컨테이너와 복구용 stopped 컨테이너·local-only 이미지·디스크 snapshot은 의도적으로 보존했다.
