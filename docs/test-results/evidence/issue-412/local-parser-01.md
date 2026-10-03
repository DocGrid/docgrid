# 파서 경합 회귀 — ha412-parser-local-01

- 목적: k6 소수초 1·5·9자리와 시간대 변환을 동일 UTC 초로 묶고, URL 태그·누락 Point를 거부하는지 확인.
- 위치/환경: 로컬 개발 머신 Python 3.14.6. 실제 실패를 낸 GCP 부하 VM은 Python 3.9이므로 로컬 결과만으로 호환성을 주장하지 않는다.
- 명령: `python3 -m unittest scripts/opensql/test_ha_load_telemetry.py -v`.
- 성공 기준: 신규 5자리 사례를 포함한 모든 테스트 통과.
- 결과: **7/7 통과**, 실패·건너뜀 0. 이후 GCP Python 3.9에서 이전 실행의 **58,920 Point**를 같은 원본으로 재집계해 HTTP **4,200**·미전송 **0**·표본 **61초**와 일치함을 별도 확인했다.
- 비밀·주소: 테스트 fixture에 실제 URL/토큰/내부 IP 없음.
