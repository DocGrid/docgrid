# 소켓 A 세 번째 반대 방향 표본 — `rag473-crossnode-r5`

- 목적: 같은 고정 경로의 마지막 반복에서 Worker B 처리 표본을 찾는다.
- 위치·시각: 로컬 전용 IAP SSH 포워드→앱 A/B, 2026-10-10 약 05:50 KST.
- 관측: query #85 `PROCESSING→FAILED`, A 소켓 `MESSAGE=1`; Worker fallback은 **A 1건·B 0건**.
- 판정: 로컬 전달 통과, 반대 방향 교차는 미관측. 반대 방향을 실측했다고 주장하지 않는다. IAP 터널 둘 다 종료 후 로컬 포트 닫힘을 확인했다.
