#!/usr/bin/env python3
"""#545 권한 필터 × 벡터 검색 누락 시험 결과(results.json)를 CSV·Markdown 표로 바꾼다.

표준 라이브러리만 쓴다. 사용법:
    python3 make_report.py <results.json 또는 results.json.gz> <출력 폴더>

출력:
    rows.csv.gz  질문 한 건 × 조합 한 개의 측정 행 (반복 측정 지연 표본 포함)
    summary.csv  규모 × 허용 비율 × topK × 방식 조합별 요약
    tables.md    결과 문서에 옮겨 쓸 Markdown 표
    h1-final-results.svg   허용 비율별 최종 결과 수 (H1)
    h2-sql-candidates.svg  허용 100%에서 SQL 반환 후보 수 (H2)
"""
import csv
import gzip
import json
import os
import statistics
import sys

METHOD_ORDER = [
    "exact", "planner_default", "planner_default_generic",
    "hnsw_ef40", "hnsw_ef100", "hnsw_ef200", "iterative_relaxed", "iterative_strict",
]
METHOD_NAMES = {
    "exact": "Exact (인덱스 없음)",
    "planner_default": "현재 계획 (Custom Plan)",
    "planner_default_generic": "현재 계획 (Generic Plan)",
    "hnsw_ef40": "HNSW 강제 ef40",
    "hnsw_ef100": "HNSW 강제 ef100",
    "hnsw_ef200": "HNSW 강제 ef200",
    "iterative_relaxed": "HNSW 강제 iterative relaxed",
    "iterative_strict": "HNSW 강제 iterative strict",
}


def ratio_label(ratio):
    return f"{ratio * 100:g}%"


def fmt(value, digits=2):
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def write_rows(report, path):
    columns = [
        "scale", "ratio", "permittedDocuments", "permittedChunks", "topK", "candidateLimit", "method",
        "questionId", "questionSource", "sqlRows", "vectorFinal", "hybridFinal", "hybridLexicalRows",
        "exactSqlRows", "exactVectorFinal", "exactHybridFinal", "sqlRecall", "vectorFinalRecall",
        "hybridFinalRecall", "vectorMedianMillis", "hybridMedianMillis", "stable", "planUsesHnsw",
        "planHnswActualRows", "planHnswRowsRemovedByFilter", "planResultRows", "planPlanningMillis",
        "planExecutionMillis", "planSharedHitBlocks", "planSharedReadBlocks", "planNodeTypes",
        "vectorMillisSamples", "hybridMillisSamples",
    ]
    with gzip.open(path, "wt", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(columns)
        for row in report["rows"]:
            plan = row["plan"]
            writer.writerow([
                row["scale"], row["ratio"], row["permittedDocuments"], row["permittedChunks"], row["topK"],
                row["candidateLimit"], row["method"], row["questionId"], row["questionSource"], row["sqlRows"],
                row["vectorFinal"], row["hybridFinal"], row["hybridLexicalRows"], row["exactSqlRows"],
                row["exactVectorFinal"], row["exactHybridFinal"], row["sqlRecall"], row["vectorFinalRecall"],
                row["hybridFinalRecall"], round(statistics.median(row["vectorMillis"]), 3),
                round(statistics.median(row["hybridMillis"]), 3), row["stable"], plan["usesHnsw"],
                plan["hnswActualRows"], plan["hnswRowsRemovedByFilter"], plan["resultRows"],
                plan["planningMillis"], plan["executionMillis"], plan["sharedHitBlocks"],
                plan["sharedReadBlocks"], " > ".join(plan["nodeTypes"]),
                ";".join(f"{value:.3f}" for value in row["vectorMillis"]),
                ";".join(f"{value:.3f}" for value in row["hybridMillis"]),
            ])


def write_summary(report, path):
    summaries = report["summaries"]
    columns = list(summaries[0].keys())
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        for summary in summaries:
            writer.writerow(summary)


def summary_index(report):
    return {(s["scale"], s["ratio"], s["topK"], s["method"]): s for s in report["summaries"]}


def tables(report):
    index = summary_index(report)
    lines = []
    scales = [scale["scale"] for scale in report["scales"]]
    ratios = report["configuration"]["ratios"]
    top_ks = report["configuration"]["topKs"]
    methods = [m for m in METHOD_ORDER if any(s["method"] == m for s in report["summaries"])]

    for scale in scales:
        info = next(s for s in report["scales"] if s["scale"] == scale)
        lines.append(f"### 규모 {scale}: 문서 {info['documents']:,}건, 청크 {info['chunks']:,}개 "
                     f"(합성 비율 {info['syntheticChunkRatio'] * 100:.1f}%)")
        lines.append("")
        lines.append("| 허용 비율 | 허용 문서 | 허용 청크 |")
        lines.append("|---:|---:|---:|")
        for ratio in info["ratios"]:
            lines.append(f"| {ratio_label(ratio['ratio'])} | {ratio['permittedDocuments']:,} | "
                         f"{ratio['permittedChunks']:,} |")
        lines.append("")

        for top_k in top_ks:
            lines.append(f"#### {scale} · topK {top_k} (SQL 요청 {top_k * report['searchSettings']['candidatePoolMultiplier']}개)")
            lines.append("")
            lines.append("| 허용 비율 | 방식 | HNSW 사용 | SQL 후보 평균 (최소~최대) | 벡터 최종 평균 | topK 미달 질문 "
                         "| 하이브리드 최종 평균 | SQL Recall | 벡터 최종 Recall (최소) | 하이브리드 최종 Recall | 벡터 p50 / p95 ms "
                         "| 하이브리드 p50 / p95 ms |")
            lines.append("|---:|---|---:|---|---:|---:|---:|---:|---|---:|---|---|")
            for ratio in ratios:
                for method in methods:
                    s = index.get((scale, ratio, top_k, method))
                    if s is None:
                        continue
                    lines.append(
                        f"| {ratio_label(ratio)} | {METHOD_NAMES[method]} | {s['hnswPlanQuestions']}/{s['questions']} | "
                        f"{s['sqlRowsMean']:.1f} ({s['sqlRowsMin']}~{s['sqlRowsMax']}) | {s['vectorFinalMean']:.1f} | "
                        f"{s['vectorShortQuestions']}/{s['questions']} | {s['hybridFinalMean']:.1f} | "
                        f"{fmt(s['sqlRecallMean'])} | {fmt(s['vectorFinalRecallMean'])} ({fmt(s['vectorFinalRecallMin'])}) | "
                        f"{fmt(s['hybridFinalRecallMean'])} | {s['vectorP50Millis']:.1f} / {s['vectorP95Millis']:.1f} | "
                        f"{s['hybridP50Millis']:.1f} / {s['hybridP95Millis']:.1f} |")
            lines.append("")
    return "\n".join(lines)


# 범주형 색은 고정 순서로만 쓴다(dataviz 기준 팔레트 1~4번, 밝은/어두운 화면 각각 검증).
SERIES_COLORS = [("#2a78d6", "#3987e5"), ("#eb6834", "#d95926"), ("#1baf7a", "#199e70"), ("#eda100", "#c98500")]
SVG_STYLE = """<style>
.surface{fill:#fcfcfb}.ink{fill:#0b0b0b}.ink2{fill:#52514e}.grid{stroke:#e4e3de}.axis{stroke:#b9b8b1}
.ref{stroke:#8f8e87}.ring{stroke:#fcfcfb}
%s
@media (prefers-color-scheme: dark){.surface{fill:#1a1a19}.ink{fill:#f0efec}.ink2{fill:#c3c2b7}
.grid{stroke:#33332f}.axis{stroke:#5b5a54}.ref{stroke:#8a897f}.ring{stroke:#1a1a19}%s}
text{font-family:-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Noto Sans KR',sans-serif}
</style>"""


def svg_style(count):
    # 선(.l)은 stroke만, 표식(.d)은 fill만 계열 색을 쓴다. 표식 테두리는 .ring(화면 색)이 맡는다.
    light = "".join(f".l{i}{{stroke:{c[0]}}}.d{i}{{fill:{c[0]}}}" for i, c in enumerate(SERIES_COLORS[:count]))
    dark = "".join(f".l{i}{{stroke:{c[1]}}}.d{i}{{fill:{c[1]}}}" for i, c in enumerate(SERIES_COLORS[:count]))
    return SVG_STYLE % (light, dark)


def text_width(text, size=12):
    """한글은 글자 크기만큼, 그 밖은 0.58배로 어림한 글자 폭."""
    return sum(size if ord(char) > 0x1100 else size * 0.58 for char in str(text))


def esc(text):
    return str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def line_panels_svg(title, subtitle, panels, series_names, x_labels, y_label, path, panel_width=280):
    """패널마다 같은 x 범주에 여러 계열의 선을 그린다. panels = [(패널 제목, y 최댓값, 참조선 값|None, [[값...] 계열별])]."""
    panel_height = 210
    left, top, gap = 56, 112, 36
    legend_width = 20 + sum(24 + text_width(name) + 22 for name in series_names)
    width = int(max(left + len(panels) * panel_width + (len(panels) - 1) * gap + 24, legend_width,
                    40 + text_width(title, 16), 40 + text_width(subtitle)))
    height = top + panel_height + 64
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" width="{width}" height="{height}" '
           f'role="img" aria-label="{esc(title)}">', svg_style(len(series_names)),
           f'<rect class="surface" width="{width}" height="{height}" rx="8"/>',
           f'<text class="ink" x="20" y="28" font-size="16" font-weight="600">{esc(title)}</text>',
           f'<text class="ink2" x="20" y="48" font-size="12">{esc(subtitle)}</text>']
    # 범례: 색 표식 옆에 글자(글자는 계열 색을 쓰지 않는다)
    legend_x = 20
    for index, name in enumerate(series_names):
        out.append(f'<line class="l{index}" x1="{legend_x}" y1="70" x2="{legend_x + 18}" y2="70" stroke-width="2" '
                   f'stroke-linecap="round"/>' + (f'<circle class="l0 surface" cx="{legend_x + 9}" cy="70" r="5" stroke-width="2"/>'
                   if index == 0 else f'<circle class="d{index} ring" cx="{legend_x + 9}" cy="70" r="4" stroke-width="2"/>'))
        out.append(f'<text class="ink2" x="{legend_x + 24}" y="74" font-size="12">{esc(name)}</text>')
        legend_x += 24 + text_width(name) + 22
    for panel_index, (panel_title, y_max, reference, values) in enumerate(panels):
        x0 = left + panel_index * (panel_width + gap)
        plot_bottom = top + panel_height
        step = panel_width / (len(x_labels) - 1) if len(x_labels) > 1 else 0
        out.append(f'<text class="ink" x="{x0}" y="{top - 14}" font-size="13" font-weight="600">{esc(panel_title)}</text>')
        ticks = nice_ticks(y_max)
        for tick in ticks:
            y = plot_bottom - tick / ticks[-1] * panel_height
            out.append(f'<line class="grid" x1="{x0}" y1="{y:.1f}" x2="{x0 + panel_width}" y2="{y:.1f}" stroke-width="1"/>')
            # 패널마다 y 범위(topK)가 다르므로 눈금 글자를 패널마다 붙인다.
            out.append(f'<text class="ink2" x="{x0 - 8}" y="{y + 4:.1f}" font-size="11" text-anchor="end">{tick:g}</text>')
        out.append(f'<line class="axis" x1="{x0}" y1="{plot_bottom}" x2="{x0 + panel_width}" y2="{plot_bottom}" stroke-width="1"/>')
        if reference is not None:
            y = plot_bottom - reference / ticks[-1] * panel_height
            out.append(f'<line class="ref" x1="{x0}" y1="{y:.1f}" x2="{x0 + panel_width}" y2="{y:.1f}" stroke-width="1" '
                       f'stroke-dasharray="4 3"/><text class="ink2" x="{x0 + panel_width}" y="{y - 5:.1f}" font-size="11" '
                       f'text-anchor="end">topK {reference:g}</text>')
        for label_index, label in enumerate(x_labels):
            x = x0 + label_index * step
            # 양 끝 글자가 그림 밖으로 잘리지 않게 첫 글자는 왼쪽, 마지막 글자는 오른쪽에 맞춘다.
            anchor = "start" if label_index == 0 else "end" if label_index == len(x_labels) - 1 else "middle"
            out.append(f'<text class="ink2" x="{x:.1f}" y="{plot_bottom + 18}" font-size="11" text-anchor="{anchor}">{esc(label)}</text>')
        # 첫 계열(기준선)은 다른 계열과 겹쳐도 보이도록 맨 나중에, 속이 빈 큰 표식으로 그린다.
        for series_index in list(range(1, len(values))) + [0]:
            series_values = values[series_index]
            points = [(x0 + i * step, plot_bottom - (v or 0) / ticks[-1] * panel_height) for i, v in enumerate(series_values)]
            out.append(f'<polyline class="l{series_index}" fill="none" stroke-width="2" stroke-linejoin="round" '
                       f'stroke-linecap="round" points="{" ".join(f"{x:.1f},{y:.1f}" for x, y in points)}"/>')
            for (x, y), value, label in zip(points, series_values, x_labels):
                tip = f'<title>{esc(series_names[series_index])} · {esc(label)}: {value:.1f}</title>'
                if series_index == 0:
                    out.append(f'<circle class="l0" fill="none" cx="{x:.1f}" cy="{y:.1f}" r="6.5" stroke-width="2">{tip}</circle>')
                else:
                    out.append(f'<circle class="d{series_index} ring" cx="{x:.1f}" cy="{y:.1f}" r="4" stroke-width="2">{tip}</circle>')
    out.append(f'<text class="ink2" x="20" y="{height - 14}" font-size="11">{esc(y_label)}</text>')
    out.append("</svg>")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(out) + "\n")


def nice_ticks(maximum):
    for step in (1, 2, 5, 10, 20, 25, 50, 100):
        if maximum / step <= 5:
            top = step * -(-maximum // step)
            return [step * i for i in range(int(top / step) + 1)]
    return [0, maximum]


def charts(report, output):
    index = summary_index(report)
    scale = "50000" if any(s["scale"] == "50000" for s in report["scales"]) else report["scales"][-1]["scale"]
    ratios = report["configuration"]["ratios"]
    top_ks = report["configuration"]["topKs"]
    multiplier = report["searchSettings"]["candidatePoolMultiplier"]

    # 그림 1 (H1): 허용 비율이 낮아질 때 사용자에게 돌아가는 최종 결과 수
    methods = ["exact", "hnsw_ef40", "hnsw_ef200", "iterative_relaxed"]
    names = ["Exact = 현재 계획", "HNSW 강제 ef40", "HNSW 강제 ef200", "HNSW 강제 iterative relaxed"]
    panels = []
    for top_k in top_ks:
        values = [[index[(scale, ratio, top_k, method)]["vectorFinalMean"] for ratio in ratios] for method in methods]
        panels.append((f"topK {top_k}", top_k, top_k, values))
    line_panels_svg(
        f"허용 문서 비율별 벡터 검색 최종 결과 수 ({int(scale):,} 청크)",
        "질문 30개 평균. HNSW 계열은 enable_sort=off로 HNSW를 쓰게 한 값이며, 현재 운영 계획은 Exact와 같은 결과를 낸다.",
        panels, names, [ratio_label(r) for r in ratios], "y축: 앱 필터(최소 유사도·문서당 2개) 뒤 최종 결과 수 평균",
        os.path.join(output, "h1-final-results.svg"))

    # 그림 2 (H2): 권한 필터가 없는(100%) 경우 SQL이 돌려준 후보 수
    methods = ["exact", "hnsw_ef40", "hnsw_ef100"]
    names = ["Exact = 요청 수", "HNSW 강제 ef40", "HNSW 강제 ef100"]
    values = [[index[(scale, 1.0, top_k, method)]["sqlRowsMean"] for top_k in top_ks] for method in methods]
    line_panels_svg(
        "허용 100%에서 SQL이 돌려준 후보 수",
        f"{int(scale):,} 청크, 질문 30개 평균. SQL 요청 수는 topK × {multiplier}이다. HNSW 강제 ef100은 Exact와 같은 값이라 겹친다.",
        [("topK별 SQL 반환 후보", max(top_ks) * multiplier, None, values)], names,
        [f"topK {k} (요청 {k * multiplier})" for k in top_ks], "y축: SQL 반환 후보 수 평균",
        os.path.join(output, "h2-sql-candidates.svg"), panel_width=480)


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    opener = gzip.open if sys.argv[1].endswith(".gz") else open
    with opener(sys.argv[1], "rt", encoding="utf-8") as handle:
        report = json.load(handle)
    output = sys.argv[2]
    os.makedirs(output, exist_ok=True)
    write_rows(report, os.path.join(output, "rows.csv.gz"))
    write_summary(report, os.path.join(output, "summary.csv"))
    with open(os.path.join(output, "tables.md"), "w", encoding="utf-8") as handle:
        handle.write(tables(report) + "\n")
    charts(report, output)


if __name__ == "__main__":
    main()
