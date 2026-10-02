# 실행 로그: GCP VM·앱 LB 읽기 전용 조회

| 필드 | 관측값 |
| --- | --- |
| 실행 ID·시각 | `ha395-cloud-topology-01`, 2026-10-03 03:40:13~03:40:18 KST |
| 목적·위치 | DB 시험 전에 VM 실행·HTTP LB 편입 확인 · 개발자 컴퓨터에서 GCP 읽기 API 호출 |
| 명령·비식별화 | `gcloud compute instances list --format=json`; `gcloud compute backend-services get-health ... --format=json`. 이름·주소·프로젝트를 출력하지 않고 역할·상태만 집계 |
| 성공 기준 | DB VM 3/3 RUNNING, 앱 VM 2/2 RUNNING, 앱 LB 2/2 HEALTHY; 쿼리 오류 0 |
| 원본 관측 요약 | DB **3/3**, 앱 **2/2**, 부하 VM **1/1**, Redis VM **1/1** RUNNING. 앱 LB backend **2/2 HEALTHY**. 쿼리 오류 **0건** |
| 해석 | VM·HTTP 앱 상태 관측은 통과. Patroni/etcd·PostgreSQL 복제·승격 자격은 전혀 증명하지 않음 |
| 변경·원복 | GCP 자원 변경 없음 |
