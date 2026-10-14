# 디버거 중단 지점 관찰

- 실행 ID: `VU-GCS-DB-CRASH-20261014-R1`
- 설정 명령: `stop at com.opensource.docgrid.domain.document.service.DocumentVersionUploadFacade:115`
- 첫 v2 HTTP 요청을 보낸 뒤 대화형 `jdb` 출력에서 다음 메시지를 실시간으로 확인했다.

```text
Breakpoint hit: "thread=http-nio-[격리 앱 포트]-exec-6", com.opensource.docgrid.domain.document.service.DocumentVersionUploadFacade.uploadClaimed(), line=115 bci=61
```

`115`행은 `storeCandidate()`가 반환된 다음 `persist(createCommand(...))`를 호출하기 직전이다. 스레드가 이 지점에서 정지한 동안 `paused_before_db.safe.json`을 수집했고, GCS 객체 2개 중 새 후보 1개에는 DB `file_objects` 참조가 없었다. 그 뒤 격리 JVM에만 `SIGKILL`을 보냈다.

이 파일은 실행 후 대화형 디버거에서 관찰한 메시지를 수동으로 옮긴 기록이다. 디버거 자체의 원본 시각은 남지 않았으므로 중단 시각은 첫 요청 시작과 `paused_before_db` 스냅샷 사이로만 한정한다.
