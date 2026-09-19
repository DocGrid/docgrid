# 저장소 고아 Object 읽기 전용 탐지 검증

## 검증 대상

- Local·MinIO·S3의 `documents/` 목록 조회와 Metadata 변환
- UUID Key 형식, 허용 확장자, 활성 Provider·Bucket, Last-Modified, 72시간 유예 조건
- 페이지 크기별 `file_objects` Key Projection Bulk Query
- 미참조 후보 개수·용량 집계와 저장소 무변경 보장
- 실패 시 마지막 정상 Micrometer Snapshot 보존
- Scheduler 성공·실패 경로와 Prometheus 규칙 YAML

## 실행 환경

- Java 17
- Gradle 8.14.5
- 실행일: 2026-09-19
- Docker Desktop Daemon과 로컬 PostgreSQL은 실행되지 않은 상태

## 실행 결과

### 기능·회귀 단위 테스트

```bash
./gradlew test \
  --tests '*StorageOrphan*' \
  --tests '*FileStorageServiceTest' \
  --tests '*StorageServiceTest'
```

결과: **성공**. 관련 6개 테스트 클래스의 28개 테스트가 실패 없이 끝났다. 새 탐지 테스트는 다음을
확인했다.

- 오래된 DB 참조 Object 1건과 미참조 Object 2건을 구분했다.
- 미참조 후보 2건의 합계 50 Byte를 계산했다.
- 최근 Object 1건과 형식·시각이 유효하지 않은 Object 2건을 후보에서 제외했다.
- 페이지 크기 2에서 DB Key Projection Query를 2회로 나눴다.
- 정상·실패 경로 모두 `FileStorageService.delete()`를 호출하지 않았다.
- 두 번째 DB 비교 실패 시 Stream을 닫고 부분 결과를 반환하지 않았다.
- 실패 Counter를 올려도 마지막 정상 후보 수·용량·Snapshot 시각을 보존했다.

### 전체 기본 테스트

```bash
./gradlew test
```

결과: 테스트 Source 전체 컴파일과 1,131개 테스트 실행까지 진행했다. 1,028개가 통과했고 103개가
실패했다. 실패는 공통적으로 테스트 Profile이 요구하는 PostgreSQL 연결 단계의
`java.net.ConnectException`에서 발생했다. 이번 변경의 관련 단위 테스트 실패는 없었다. 실제
PostgreSQL을 사용하는 Repository·통합 회귀는 DB 실행 환경에서 다시 확인해야 한다.

### 설정 문법

```bash
docker compose config --quiet
ruby -e 'require "yaml"; YAML.load_file("monitoring/prometheus/rules/docgrid-backend-alerts.yml")'
```

결과: Compose 설정과 Prometheus 규칙 YAML 파싱이 성공했다. `promtool check rules`는 Docker
Daemon이 실행되지 않아 수행하지 못했다. Prometheus 컨테이너를 사용할 수 있는 CI 또는 로컬 환경에서
PromQL 규칙 검증을 한 번 더 실행해야 한다.

## 남은 실제 저장소 검증

이번 단계는 SDK 경계까지 단위 테스트했다. 실제 MinIO·S3 환경에서 다음 수치를 측정하는 검증은 별도로
남아 있다.

- Object 수에 따른 전체 스캔 시간과 목록 API 요청 수
- 페이지 크기별 DB Query 수와 실행 시간
- 실제 오래된 미참조 Object의 후보 수·용량
- 동일 조건에서 참조 중 Object 오판정 0건과 물리 삭제 0건
