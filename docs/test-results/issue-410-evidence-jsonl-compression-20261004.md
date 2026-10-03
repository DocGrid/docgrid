# 대용량 시험 표본 JSONL 무손실 압축·참조 검증

- 관련 이슈: [#410](https://github.com/DocGrid/docgrid/issues/410)
- 원문 비교 기준: [압축 전 develop 커밋 `cd146ad6`](https://github.com/DocGrid/docgrid/tree/cd146ad6a81aff1415ae52710e22b77df82de00f)
- 실행일: 2026-10-04 KST
- 범위: 기존 시험 #378의 변경 후 50명 실행 3개와 #380의 50명 실행 3개에 속한 비식별 `samples.jsonl` **6개만**. 측정값·요약·실행 원장·다른 파일은 변경하지 않았다.
- 목적: Git의 현재 체크아웃에 남는 원시 표본 크기를 줄이면서 과거 시험을 바이트 단위로 복원할 수 있게 한다.

## 실행 방법과 결과

| 실행 ID | 실행 위치·절차 | 완료 기준 | 실제 결과·해석 |
| --- | --- | --- | --- |
| `issue410-compression-01` | 분리된 로컬 worktree에서 6개 원문마다 `gzip -n -c <원문> > <원문>.gz` → `gzip -t` → `cmp <원문> <(gzip -dc <압축본>)` → 양쪽 SHA-256·바이트 수 기록 | 6개 모두 무손실·해시·크기 확인 후에만 Git의 원문을 제거 | `gzip -t` **6/6 PASS**, 압축 해제 후 `cmp` **6/6 PASS**. 원문 5,818,653바이트 → 압축본 285,171바이트, 현재 체크아웃에서 5,533,482바이트(**95.10%**) 절감. [실행별 로그](evidence/issue-410/compression-verification-01.txt)·[검증 manifest](evidence/issue-410/compression-manifest.csv) |
| `issue410-reference-01` | 압축본 6개를 다시 해제해 manifest의 원문 SHA-256·바이트 수와 대조하고, 결과 문서의 대상 링크·Git 변경 범위 확인 | 6개 복원 일치, 6개 링크 유효, 대상 외 파일 삭제 0 | 압축 검사·원문 SHA-256·압축본 SHA-256·크기·기존 커밋에서 복구 가능 여부 **각 6/6 PASS**. 변경된 문서 링크 **6/6 PASS**, 원본 제거 정확히 6개. [별도 로그](evidence/issue-410/reference-verification-01.txt), UTC 2026-10-03 16:08:31 기록 |
| `issue410-history-01` | `git show origin/develop:<원문> \| cmp - <(gzip -dc <압축본>)`으로 현재 브랜치에서 제거한 원문을 기준 커밋에서 다시 읽어 대조 | 6개 모두 기준 커밋과 바이트 단위 동일 | **6/6 PASS**. [기존 커밋 직접 대조 로그](evidence/issue-410/history-verification-01.txt), UTC 2026-10-03 16:10:05 기록 |

각 원본과 압축본의 상대 경로, 바이트 수, SHA-256은 [manifest](evidence/issue-410/compression-manifest.csv)에 있다. `gzip -n`은 아카이브 헤더에 원본 파일명·시각을 넣지 않아 같은 입력을 다시 압축해 비교하기 쉽다. GitHub에서는 `.gz`를 직접 읽는 대신 내려받아 `gzip -dc`로 확인한다. 사람이 바로 읽을 수 있는 핵심 숫자·실패 해석은 기존 한국어 [시험 #378 결과](gimin-378-dashboard-batch-authorization-gcp-load-20261002.md)와 [시험 #380 결과](gimin-380-gcp-ab-websocket-20261002.md)에 그대로 남긴다.

```text
비식별 samples.jsonl 6개 (원문 5,818,653바이트)
       │ gzip -n: 내용 압축, 원본 이름·시각 헤더 제외
       ▼
samples.jsonl.gz 6개 (합계 285,171바이트)
       │ gzip -t + 해제 후 원문 cmp + SHA-256 대조
       ▼
과거 수치·이벤트를 바이트 단위로 복원 가능
```

## 경계

- `git rm`은 **새 브랜치의 현재 트리**에서만 원문을 없앤다. 기존 커밋의 blob은 Git 역사에 남으므로 GitHub 저장소 크기나 로컬 `.git` 크기가 이 커밋만으로 곧바로 95.10% 줄어드는 것은 아니다. SHA를 바꾸는 역사 재작성은 하지 않았다.
- 새 부하 시험에서 원시 JSONL을 계속 커밋하면 다시 커진다. 이번 변경은 과거 큰 파일 6개에 한정하며, 이후 증거 보관 정책이나 Git 객체 정리는 별도 검토 대상이다.
- 요약·manifest·요청 ID 원장·DB 대조 원본을 삭제하지 않았다. 파일의 공개 범위도 바꾸지 않았다.
