# 소켓 A 첫 반대 방향 표본 — `rag473-crossnode-r3`

- 목적: 앱 A 소켓·앱 B 검색 조건에서 실제 Worker B를 확보한다.
- 위치·시각: 로컬 전용 IAP SSH 포워드→앱 A/B, 2026-10-10 약 05:49 KST.
- 관측: query #83 `PROCESSING→FAILED`, A 소켓 `MESSAGE=1`; Worker fallback은 **A 1건·B 0건**.
- 판정: 소켓·Worker 모두 A라 반대 방향 교차 증거는 아님. 검색 요청을 B에 보냈다는 사실만으로 Worker도 B라고 간주하지 않는다.
