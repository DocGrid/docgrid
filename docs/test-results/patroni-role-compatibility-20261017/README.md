# 격리 OpenProxy의 Patroni 동기 역할 호환 경계

## 실제 발견

원본 OpenProxy는 strict 지정형 동기의 `sync_standby` 역할을 역직렬화하지 못해 종료 코드 **78**로 시작에 실패했습니다. HTTP·TCP 연결 행렬은 모든 후보 3개에서 성공했고 scope도 일치했습니다. 연결 장애와 parser 호환 결함을 구분했습니다.

- [지정형 원본 관측](vendor-designated-run01.log)
- [quorum 원본 관측](vendor-quorum-run01.log): 실제 `synchronous_mode=quorum`, strict=true, node_count=1 적용 후 native 역할과 원본 parser 결과를 기록합니다.
- [어댑터 HTTP 계약](adapter-http-run01.log): **9개, 실패 0개**. 실제 로컬 HTTP 상류·어댑터를 사용했습니다.

## 어댑터 경계

Python 표준 라이브러리만 사용합니다. 원본 바이너리·Patroni·DB는 고치지 않습니다. 매 GET에서 fresh `/patroni`의 scope를 확인하고 `/cluster`의 `sync_standby`·`quorum_standby`를 `replica`로만 변환합니다. leader·primary와 나머지 Metadata는 보존합니다. 모르는 역할·다른 scope·상류 실패는 502, 변경 요청은 405입니다. 로그에 원시 주소나 payload를 남기지 않습니다. 지정한 private IPv4에만 바인딩합니다.

```text
원본 OpenProxy → native /cluster → sync_standby → parser 거부
원본 OpenProxy → 읽기 전용 어댑터 → fresh scope 확인
                                  → standby 역할 별칭만 replica로 변환
                                  → Primary/Leader는 그대로 전달
```

## 한계

이는 **격리 시험용 호환 계층**이며 공급사 공식 quorum 지원이나 운영 도입 완료가 아닙니다. 실제 HA 승격·쓰기 보존은 후속 격리 부하에서 검증합니다. 어댑터가 추가된 경로와 원본 그대로의 실패를 구분해야 합니다. 공개 OpenProxy·클러스터에는 적용하지 않았습니다.
