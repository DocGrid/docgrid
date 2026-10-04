# 실행 기록: etcd 백업·격리 복원 관문

- 시각: 2026-10-04 UTC, 장애 주입 이전. 개별 명령의 원본 초 단위 시각은 별도 저장하지 않았다.
- 위치: 로컬 제어면에서 공식 release 검증; node1 VM의 root 전용 디렉터리와 DB 컨테이너에서 백업·복원.
- 목적: 정족수 장애 전에 현재 DCS snapshot이 실제로 열리고 별도 데이터 디렉터리에서 기동되는지 확인.
- 성공 조건: archive SHA-256 일치, snapshot status 성공, 운영 클러스터에 합류하지 않는 격리 etcd health `true`와 동일 revision.

| 명령·절차 | 결과 요약 | 해석 |
| --- | --- | --- |
| 공식 etcd 3.6.5 Linux archive·`SHA256SUMS` 다운로드, `shasum -a 256` 비교 | archive SHA-256 `66bad39ed920f6fc15fd74adcb8bfd38ba9a6412f8c7852d09eb11670e88cac3` 일치 | 복원 도구의 버전·무결성 확인 |
| 컨테이너 `etcdctl snapshot save` | snapshot **2,314,272 B**, root 전용 권한 | 시험 직전 현재 DCS 상태 백업 |
| 공식 `etcdutl snapshot status` | revision **6699**, 키 **10개**, 상태 읽기 성공 | 파일 파싱 가능 |
| `etcdutl snapshot restore` | 별도 디렉터리 복원 성공 | 운영 etcd의 data-dir 미변경 |
| 최초 격리 etcd 기동 | **실패:** SELinux가 임시 디렉터리 실행 파일을 거부 | 운영 etcd 장애가 아님; 원인 숨기지 않음 |
| SELinux 실행 레이블 위치에 검증된 바이너리 설치 후 격리 기동 | health **true**, revision **6699**, 127.0.0.1 전용 포트 | 복원본의 실제 기동 검증 |
| 격리 서비스 중지·포트 확인 | 포트 닫힘 | 운영 클러스터에 테스트 멤버 잔류 없음 |

제한: 이 복원은 **동일 VM의 격리 디렉터리**에서 이루어졌다. 별도 VM·스토리지에서의 재해 복구 성공으로 확장하지 않는다. 기존 DB 디스크의 READY snapshot은 노드별 3/2/2개 확인했지만, 이번 실행에서 그 snapshot의 복원은 하지 않았다. etcd snapshot 내용·클러스터 키·내부 주소는 기록하지 않았다.

장애 시험·원복 확인 후 이 시험에서 만든 root 전용 etcd snapshot과 격리 복원 데이터는 삭제했다. 원본 etcd 운영 데이터와 기존 GCP 부팅 디스크 snapshot은 삭제하지 않았다. 현재 snapshot 파일은 남아 있지 않으므로 이후 장애 시험에서는 **새 백업과 격리 복원 관문을 다시 실행해야 한다**.
