# 실제 HTTP 회수 확정·승격 후 권한 차단

## 결과

**격리 OpenSQL의 자동 승격 뒤 실제 인증 HTTP 차단 시험이 통과했습니다.** [최종 원본 응답](http-primary-run04/http.jsonl), [장애 제어](http-primary-run04/control.jsonl), [애플리케이션 실행](http-primary-run04/app.log), [집계](aggregate.json).

| 검증 | 실제 결과 |
| --- | --- |
| 실제 로그인 | 소유자·대상 사용자 각 200, 토큰은 메모리에서만 사용 |
| 인증 없는 상세 | 401 |
| 회수 전 | 두 문서 상세·본문·파일 각 200, 새 검색 결과·합성 근거 각 2개 |
| 실제 회수 | 동일 키 회수 204, 그 뒤 시험 목적으로 stale 캐시 복원 |
| 자동 승격 뒤 대상 | 상세·본문·파일 3회씩, **403 9개** |
| 자동 승격 뒤 대조 | 상세·본문·파일 3회씩, **200 9개**, 파일 해시 일치 |
| 자동 승격 뒤 새 검색 | **200 3개**, 매번 대조 결과·합성 근거 1개, 대상 문구·문서 ID 제외 |
| 금지 응답 검출 | 승격 뒤 21개 새 HTTP 응답에서 **0건** |
| 최종 원장 | 현재 Primary에서 요청 키 1개·회수 대상 권한 행 0개 |

종료 확인→자동 승격 관측은 **19.452초**입니다. 로컬 JVM→IAP 경로이고 전체 서비스 RTO로 표현하지 않습니다. 이전 시험 Primary는 중지한 상태에서 새 Primary 경로를 확인했습니다.

## 실제 경로와 대역

```text
실제 /auth/login·BCrypt → JWT → 실제 SecurityConfig·Valkey·CurrentUser
  → 실제 HTTP Controller → JPA·권한 원장 → 격리 OpenProxy·역할 어댑터 → OpenSQL
  → 상세·본문·파일 바이트 / 새 검색 결과·통제된 합성 답변·근거
```

SecurityContext 주입이나 standalone MVC 요청이 아닙니다. 실제 루프백 Tomcat·Java HTTP client·로그인·JWT 필터를 실행했습니다. 문서 원장·버전·Chunk·Embedding·검색 접수·검색 결과 저장은 실제 DB입니다.

외부 파일 저장소는 메모리의 고유 합성 바이트, 질의 임베딩·검색 후보·RAG 응답은 명시적 시험 대역입니다. 후보 대역은 회수된 문서까지 반환하며 실제 SearchFacade의 Primary 최종 인가가 제거합니다. 실제 모델 품질·비동기 RAG Worker·GCS·공개 브라우저·과거 대화 재조회는 포함하지 않습니다. 파일 차단 때 저장소 read가 호출되지 않는지도 단언했습니다.

## 실패·재시험·수집 한계

| 회차 | 관측과 판정 |
| --- | --- |
| [run01](http-primary-run01/app.log) | 로그인·파일 조회 뒤 검색 준비 단언 실패. local 프로필의 hybrid=true가 벡터 대역을 우회. 명시적으로 false 고정. **장애 주입 없음** |
| [run02](http-primary-run02/app.log) | 재시험 fixture의 같은 파일 해시가 유일 제약 위반. 실행별 고유 바이트로 변경. **장애 주입 없음** |
| [run03](http-primary-run03/control.jsonl) | 회수 204와 자동 승격은 관측했으나 이전 Primary 재가입이 90초 제한 안에 끝나지 않아 실패. [새 Primary 키 감사](http-primary-run03/post-failure-db-audit.log)는 원장 1·권한 0을 확인했으며 해당 회차를 성공으로 바꾸지 않음 |
| run04 | 새 Primary의 HTTP 차단을 먼저 확인하고 이전 노드 재가입과 분리. 위 결과 통과 |

run03의 뒤늦은 읽기 진단에서 native Patroni가 pg_rewind 진행 중이라고 보고했습니다. 당시 재가입 지연의 정확한 원인은 확정하지 않았고 해결했다고 주장하지 않습니다. 이후 [격리 데이터·DCS 재초기화](reset-before-http-run04/reset.jsonl) 후 별도 회차로 시험했습니다. run04는 이전 노드 자동 재가입을 검증한 회차가 아닙니다.

run01 제어 로그에 복수 기록자가 추가 메타데이터를 쓰면서 한 줄이 손상됐습니다. [원본 바이트](http-primary-run01/control-capture.raw.log)와 [수집 한계](http-primary-run01/capture-notes.json)를 보존했으며 판정·시간 집계에 사용하지 않았습니다. 초기 app.log의 실행 위치 header도 기존 runner의 새 task 분기 누락이었으며 실제 경로는 이 문서와 제어 원장에 명시했습니다. 이후 회차는 단일 기록자를 사용합니다.

첫 컴파일의 타입 이름 충돌과 잘못된 enum 이름을 수정했습니다. 실패를 운영 장애로 표현하지 않습니다.

## 실행·안전 경계

- 수동 작업: `./backend/gradlew -p backend permissionHttpHaTest`. 기본 CI에서는 별도 태그로 제외합니다.
- 전용 loopback JDBC·migration URL·새 실행 디렉터리만 허용합니다. HTTP도 loopback에만 바인딩합니다.
- 기존 SSH 키를 재사용했고 새 OS Login 키·IAM 권한을 추가하지 않았습니다.
- 격리 컨테이너·데이터·포트·DCS만 변경했습니다. [준비 기록](bootstrap-run01.jsonl)과 제어 단계에서 공개 설정 hash 불변을 확인했습니다. 원본 컨테이너 이미지 캡처는 pause=false입니다.
- 원본 OpenProxy의 동기 역할 오류는 그대로이며 어댑터 경유 결과입니다. 공개 클러스터의 동기/quorum 설정이나 기능 활성화를 변경하지 않았습니다.
- 실행 코드가 커밋 전이었으므로 제어 원장에 소스·Gradle SHA-256을 보존했습니다. 최종 Java 소스 SHA-256은 `405fe0ae8bc9a09f7bf5e5f3c0f19924496ca6a85c927093bf32ed6a1c1d4b4b`입니다.
- 공용 시험 자원은 후속 이슈 #543의 같은 격리 실험에 이어 사용합니다. 최종 자원 정리는 후속 결과에 연결하며 현재 제거 완료로 표현하지 않습니다.

로그에는 로그인 원시 응답·이메일·비밀번호·JWT·Authorization·비공개 인프라 식별자를 기록하지 않았습니다. 응답 상태·지연·합성 문구 검출·해시만 보존했습니다.
