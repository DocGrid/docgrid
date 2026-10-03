# runner 인자 검증 첫 시도 — ha412-runner-local-01

- 목적: 선택적 초기 VU 인자의 범위 검증과 Bash 구문 확인.
- 위치: 로컬 개발 머신. 명령: `bash -n scripts/opensql/run_ha_probe_k6.sh && git diff --check`, 이어서 초기 VU 900으로 runner 호출.
- 성공 기준: 구문 통과, 최대 800을 넘는 초기 VU 거부.
- 결과: 구문·diff 검사 통과, runner 종료 코드 **2**. 다만 이 시도는 토큰 입력이 `/dev/null`이어서 **토큰 권한 검사도 실패할 수 있었다.** VU 거부만의 결정적 증거로 사용하지 않는다.
- 조치: 올바른 0600 임시 토큰 파일을 사용한 [두 번째 검증](local-runner-02.md)을 별도 실행했다.
