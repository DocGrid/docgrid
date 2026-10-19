# 수정 전 재현 증거

기준 소스는 `1272a016e3b83e3f028d45a3bf567dab6bee743f`다. 로컬 격리 PostgreSQL·Valkey, 실제 로그인·JWT·HTTP·권한·RAG 서비스를 사용했고 임베딩/후보/모델 응답/파일 바이트는 합성 대역이다. 공개 서비스 실제 정보 노출을 시험하지 않았다.

| 목적 | 유효 실행 | 관측 |
|---|---|---|
| 기존 답변·대화 재조회 | [AUTH-SAVED-R2](AUTH-SAVED-20261019-R2/events.jsonl) | 각각 3/3회 금지 문구 |
| 회수 후 첫 모델 전달 | [AUTH-QUEUED-R2](AUTH-QUEUED-20261019-R2/events.jsonl) | 첫 모델 입력 1/1건에 금지 문구 |
| 후속 문맥 재사용 | [AUTH-HISTORY-R3](AUTH-HISTORY-20261019-R3/events.jsonl) | 현재 결과에서 제외된 문구가 후속 모델 문맥에 존재 |

초기 준비의 vector 확장 누락 회차는 앱 기동 전 중단되어 제외했다. 후속 질문 R2는 이전 메서드의 합성 파일을 잘못 비교한 helper 오류로 미완료였고, 해당 목적은 별도 새 R3으로 재현했다. 두 목적의 R2 성공 재현과 후속 R3을 섞어 단일 실행이라고 표현하지 않는다.

당시 [R2 시험 코드](verification-harness-r2.java)와 [R3 시험 코드](verification-harness-r3.java)를 함께 보존했다. 이 폴더는 이전 검증에서 기록한 원장·응답·수신 입력을 비식별 상태로 옮긴 것이며 과거 실시간 로그를 새로 구성한 것이 아니다. [수정 후 비교·준비 오류·한계](../README.md).
