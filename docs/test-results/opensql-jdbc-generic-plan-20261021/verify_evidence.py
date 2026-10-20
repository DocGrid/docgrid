#!/usr/bin/env python3
"""DB에 접속하지 않고 기존 이벤트 원문으로 계획 분류·지연·결과·정리를 재검산한다."""
import hashlib
import json
import math
from pathlib import Path
import re
import statistics

ROOT = Path(__file__).resolve().parent
VALID = ('GP50-PROXY-A-RO-20261021-R2', 'GP50-PROXY-B-RO-20261021-R1')
EXCLUDED = 'GP50-PROXY-A-RO-20261021-R1'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(name):
    return json.loads((ROOT / name).read_text())


def events(run):
    rows = [json.loads(line) for line in (ROOT / (run + '-events.jsonl')).read_text().splitlines()]
    require(all(row['실행_ID'] == run for row in rows), run + ': 실행 ID 혼합')
    require(all(row['시각'].endswith('+09:00') for row in rows), run + ': 시간대 누락')
    return rows


def event(rows, name):
    matched = [row for row in rows if row.get('이벤트') == name]
    require(len(matched) == 1, name + ': 이벤트 수 불일치')
    return matched[0]


def distribution(rows):
    if not rows:
        return {'n': 0}
    values = sorted(row['JDBC_왕복_ms'] for row in rows)
    return {'n': len(values), 'p50_ms': statistics.median(values),
            'p95_ms': values[math.ceil(0.95 * len(values)) - 1],
            'min_ms': values[0], 'max_ms': values[-1]}


def classify(samples):
    # 누적 스냅샷을 더하지 않고 동일 SQL·backend·준비문 직전 값의 증가만 센다.
    seen = {}
    output = []
    for sample in samples:
        dg = dc = 0
        for statement in sample['계획카운터']['준비문별']:
            key = (sample['SQL'], sample['backend_별칭'], statement['준비문_별칭'])
            previous = seen.get(key, (0, 0))
            current = (statement['generic_plans'], statement['custom_plans'])
            require(current[0] >= previous[0] and current[1] >= previous[1], '준비문 카운터 역행')
            dg += current[0] - previous[0]
            dc += current[1] - previous[1]
            seen[key] = current
        plan = 'Generic' if (dg, dc) == (1, 0) else 'Custom' if (dg, dc) == (0, 1) else '미분류'
        output.append({**sample, '선택판정': plan, 'Generic_증가': dg, 'Custom_증가': dc})
    return output


def main():
    # 1. 원본·소스 스냅샷이 공개 manifest의 바이트와 같은지 확인한다.
    manifest = read_json('publication-manifest.json')
    for item in manifest['files']:
        path = (ROOT / item['file']).resolve()
        require(path.is_relative_to(ROOT), '근거 경로 범위 이탈')
        data = path.read_bytes()
        require(len(data) == item['bytes'], item['file'] + ': 크기 불일치')
        require(hashlib.sha256(data).hexdigest() == item['sha256'], item['file'] + ': SHA 불일치')
    sql_hash = hashlib.sha256((ROOT / 'source/findTopK.sql').read_bytes()).hexdigest()
    aggregate = read_json('aggregate.json')
    expected = {run['실행_ID']: run for run in aggregate['회차']}
    require(set(expected) == set(VALID) and aggregate['제외회차'] == [EXCLUDED], '집계 회차 불일치')
    hashes = set()
    summaries = []
    for run in VALID:
        rows = events(run)
        condition = event(rows, '측정 조건')
        require(condition['SQL_SHA256'] == sql_hash, run + ': SQL SHA 불일치')
        require(condition['plan_cache_mode'] == 'auto' and condition['prepareThreshold'] == 5,
                run + ': 계획 조건 불일치')
        require(condition['계측버전'] == 'GP50-R4', run + ': 계측 버전 불일치')
        loaded = event(rows, '적재 건수 확인')
        require((loaded['청크수'], loaded['차원'], loaded['문서수']) == (50008, 1024, 3559), '적재 규모 불일치')
        samples = [row for row in rows if row['이벤트'] == 'SQL 반복 관측']
        require(len(samples) == 60, run + ': 표본 수 불일치')
        require(all(row['결과동일'] and row['결과수'] == 40 for row in samples), '결과 불일치')
        require(event(rows, 'JDBC 회차 종료')['반환코드'] == 0, run + ': 비정상 종료')
        require(event(rows, '격리 Proxy 정리')['판정'] == '통과', run + ': 정리 미완료')
        classified = classify(samples)
        require(classified == read_json(run + '-classified.json')['표본'], '호출별 분류 불일치')
        require(expected[run]['총표본수'] == 60, '집계 표본 수 불일치')
        for variant in ('수정 전', '수정 후'):
            subset = [row for row in classified if row['SQL'] == variant]
            require([row['회차'] for row in subset] == list(range(1, 31)), '반복 순서 불일치')
            calculated = {plan: distribution([row for row in subset if row['선택판정'] == plan])
                          for plan in ('Custom', 'Generic', '미분류')}
            recorded = next(item for item in expected[run]['SQL별'] if item['SQL'] == variant)
            require(distribution(subset) == recorded['전체'], '전체 분포 불일치')
            require(all(calculated[key] == recorded[key] for key in calculated), '계획별 분포 불일치')
            require((calculated['Custom']['n'], calculated['Generic']['n'], calculated['미분류']['n'])
                    == (14, 16, 0), '계획 표본 수 불일치')
            first = next(row['회차'] for row in subset if row['선택판정'] == 'Generic')
            require(first == recorded['Generic_최초회차'], '최초 Generic 회차 불일치')
            plans = [row for row in rows if row['이벤트'] == '별도 EXPLAIN ANALYZE' and row['SQL'] == variant]
            require(len(plans) == 1 and plans[0]['검색계획확인'], '실행계획 누락')
            lines = plans[0]['계획']
            execution = next(float(match.group(1)) for line in lines
                             if (match := re.match(r'Execution Time: ([0-9.]+) ms', line)))
            require(execution == recorded['별도EXPLAIN'][0]['Execution_ms'], 'DB 실행시간 불일치')
            require(any('InitPlan' in line for line in lines) == (variant == '수정 후'), 'InitPlan 차이 불일치')
            require(not any('embeddings_vector_idx' in line for line in lines), 'HNSW 관측 차이')
            summaries.append({'실행_ID': run, 'SQL': variant, 'Generic_n': 16,
                              'Generic_p50_ms': calculated['Generic']['p50_ms']})
        hashes.update(row['결과_SHA256'] for row in samples)
    require(len(hashes) == aggregate['전체결과_해시종류수'] == 1, '전체 결과 해시 불일치')
    # 2. 제외 회차는 별도로 보존하며 유효 120개에 합산하지 않는다.
    excluded = events(EXCLUDED)
    require(sum(row['이벤트'] == 'SQL 반복 관측' for row in excluded) == 60, '제외 표본 수 불일치')
    require(event(excluded, 'JDBC 회차 종료')['반환코드'] == 143, '제외 회차 종료코드 불일치')
    require(not any(row['이벤트'] == '별도 EXPLAIN ANALYZE' for row in excluded), '제외 사유 차이')
    # 3. 전용 자원 정리·환경 보존은 당시 별도 로그로 확인한다. 원격에 재접속하지 않는다.
    cleanup = events('GP50-DB-CLEANUP-20261021-R1')
    require(event(cleanup, '전용 DB 삭제')['카탈로그_잔여_DB수'] == 0, '전용 DB 정리 기록 불일치')
    preserve = events('GP50-PRESERVE-20261021-R1')
    remote = [row for row in preserve if row['이벤트'] == '원격 정리 확인']
    require(len(remote) == 2 and all(row['임시_Proxy_프로세스수'] == 0 and row['시험_데이터포트_닫힘']
            and row['원본_Proxy_프로세스수'] == 1 for row in remote), 'Proxy 정리 기록 불일치')
    require(event(preserve, '로컬 보존')['개인환경_공개설정_동일'], '설정 보존 기록 불일치')
    print(json.dumps({'판정': '통과', '실행위치': '로컬·기존 기록만 재검산·DB 접속 없음',
                      '원문_해시_확인파일수': len(manifest['files']), '최종_유효표본수': 120,
                      '결과일치수': 120, '미분류수': 0, '제외표본수': 60, '결과_해시종류수': len(hashes),
                      '회차별': summaries, '한계': '이미 수집한 근거의 일관성 점검이며 새 성능·클라우드 시험 아님'},
                     ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
