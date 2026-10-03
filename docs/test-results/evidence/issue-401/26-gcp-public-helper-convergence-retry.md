# #401 비식별 도우미 이미지 태그 보정·재검증

- 실행 ID: `issue-401-helper-convergence-02`
- 목적: 앞선 원격 변수 누락을 고치고 세 VM의 기존 캡처 이미지에 공개용 node-label 태그를 추가해 설치된 도우미의 runtime 검사를 통과시킨다.
- 실행 위치: GCP DB VM 3대, DB 컨테이너 재시작 없음.
- 절차: 각 VM의 기존 이미지 ID 확인 → 같은 ID로 node-label 별칭 태그 추가 → 설치된 비식별 도우미 `verify` → 클러스터 역할·etcd 최종 확인.
- 성공 기준: 태그 ID 일치 3/3, runtime 계약 3/3, leader 1·streaming replica 2·etcd 3/3, DB 재시작 0.
- 결과: 기존/비식별 태그의 Docker 이미지 ID 일치 **3/3**, 설치된 도우미 runtime contract **3/3 통과**. DB 컨테이너 재시작 **0건**. 최종 Patroni leader **1**·streaming replica **2**, etcd healthy **3/3**.
- 판정: 공개 코드와 현재 GCP 런타임의 검증 계약이 일치한다. 첫 실패 실행은 [별도 로그](25-gcp-public-helper-convergence.md)에 보존한다.
