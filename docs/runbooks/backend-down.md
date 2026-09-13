# DocGrid Backend Down

## 의미

`DocGridBackendDown`은 Prometheus가 `docgrid-backend` target의 `/actuator/prometheus`를 1분 동안
수집하지 못했을 때 발생한다. Backend 프로세스 중단 외에도 Management 포트, 네트워크 경로,
Prometheus target 설정 오류가 원인일 수 있다.

## 확인

1. Prometheus `/targets`에서 `docgrid-backend`의 마지막 오류와 마지막 성공 시간을 확인한다.
2. Prometheus가 실행되는 위치에서 Backend Management endpoint에 접근한다.

   ```bash
   curl -f http://docgrid-backend.internal:8081/actuator/health/liveness
   curl -f http://docgrid-backend.internal:8081/actuator/prometheus
   ```

3. Backend 프로세스와 최근 종료 원인, OOM, 배포 이벤트를 확인한다.
4. Backend 로그에서 시작 실패, DB 연결 실패, 포트 충돌을 확인한다.
5. target 주소, Management 포트, 방화벽과 Security Group 변경을 확인한다.

로컬 Compose에서는 `docgrid-backend.internal` 대신 `host.docker.internal`을 사용한다.

## 복구

1. Backend가 중단됐다면 정상 배포 절차로 재시작한다.
2. 시작 실패라면 로그에 나온 설정·DB·포트 원인을 수정한 뒤 다시 시작한다.
3. Backend는 정상인데 scrape만 실패하면 target 파일과 Prometheus에서 Host까지의 네트워크 경로를
   복구한다.
4. `/actuator/health/liveness`와 `/actuator/prometheus`가 모두 200인지 확인한다.
5. Prometheus `/targets`에서 `docgrid-backend`가 `UP`으로 돌아오는지 확인한다.
6. `/alerts`에서 `DocGridBackendDown`이 inactive로 전환됐는지 확인한다.

## 복구 완료 조건

- Backend liveness와 Prometheus endpoint가 200을 반환한다.
- `up{job="docgrid-backend"}` 값이 `1`이다.
- `DocGridBackendDown`이 inactive 상태다.
- 사용자 API의 정상 요청이 성공한다.
