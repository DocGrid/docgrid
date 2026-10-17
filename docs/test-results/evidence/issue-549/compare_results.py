#!/usr/bin/env python3
"""#549 수정 전후 측정 결과(PermissionRecallBenchmark results.json)를 조합별로 비교한다. 표준 라이브러리만 쓴다.

사용법:
    python3 compare_results.py <수정 전 results.json[.gz]> <수정 후 results.json[.gz]> <출력 폴더> [접두사]

출력:
    <접두사>comparison.csv  규모 × 허용 비율 × topK × 방식 조합별 지연 p50·p95 전후값, 개선율, 결과 일치 여부
    <접두사>comparison.md   결과 문서에 옮겨 쓸 Markdown 표
    <접두사>before-after-latency.svg  5만 청크·topK 10 현재 계획 Generic Plan 지연 전후(로그 축)
    <접두사>improvement-percent.svg   같은 조건의 개선율 막대

개선율 = (수정 전 - 수정 후) / 수정 전 × 100. 양수면 빨라진 것이다.
결과 일치 = 같은 질문의 SQL 후보·벡터 최종·하이브리드 최종 청크 수와 Recall이 전후에 모두 같은지다.
"""
import csv
import gzip
import json
import math
import os
import sys

METHOD_NAMES = {
    "exact": "Exact",
    "planner_default": "현재 계획 Custom",
    "planner_default_generic": "현재 계획 Generic",
    "hnsw_ef40": "HNSW 강제 ef40",
    "hnsw_ef100": "HNSW 강제 ef100",
    "hnsw_ef200": "HNSW 강제 ef200",
    "iterative_relaxed": "iterative relaxed",
    "iterative_strict": "iterative strict",
}
RESULT_FIELDS = ["sqlRows", "vectorFinal", "hybridFinal", "sqlRecall", "vectorFinalRecall", "hybridFinalRecall"]


def load(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as handle:
        return json.load(handle)


def improvement(before, after):
    return (before - after) / before * 100 if before else None


def compare(before, after):
    before_summary = {(s["scale"], s["ratio"], s["topK"], s["method"]): s for s in before["summaries"]}
    before_rows = {(r["scale"], r["ratio"], r["topK"], r["method"], r["questionId"]): r for r in before["rows"]}
    after_rows = {}
    for row in after["rows"]:
        after_rows.setdefault((row["scale"], row["ratio"], row["topK"], row["method"]), []).append(row)

    results = []
    for summary in after["summaries"]:
        key = (summary["scale"], summary["ratio"], summary["topK"], summary["method"])
        base = before_summary.get(key)
        if base is None:
            continue
        mismatched = 0
        for row in after_rows[key]:
            old = before_rows[key + (row["questionId"],)]
            if any(old[field] != row[field] for field in RESULT_FIELDS):
                mismatched += 1
        results.append({
            "scale": summary["scale"], "ratio": summary["ratio"], "topK": summary["topK"],
            "method": summary["method"], "questions": summary["questions"],
            "vectorP50Before": base["vectorP50Millis"], "vectorP50After": summary["vectorP50Millis"],
            "vectorP50ImprovementPercent": improvement(base["vectorP50Millis"], summary["vectorP50Millis"]),
            "vectorP95Before": base["vectorP95Millis"], "vectorP95After": summary["vectorP95Millis"],
            "vectorP95ImprovementPercent": improvement(base["vectorP95Millis"], summary["vectorP95Millis"]),
            "hybridP50Before": base["hybridP50Millis"], "hybridP50After": summary["hybridP50Millis"],
            "hybridP50ImprovementPercent": improvement(base["hybridP50Millis"], summary["hybridP50Millis"]),
            "hybridP95Before": base["hybridP95Millis"], "hybridP95After": summary["hybridP95Millis"],
            "hybridP95ImprovementPercent": improvement(base["hybridP95Millis"], summary["hybridP95Millis"]),
            "hnswPlanBefore": base["hnswPlanQuestions"], "hnswPlanAfter": summary["hnswPlanQuestions"],
            "resultMismatchQuestions": mismatched,
        })
    return results


def fmt_percent(value):
    return "-" if value is None else f"{value:+.1f}%"


def markdown(results):
    lines = ["| 규모 | 허용 비율 | topK | 방식 | 벡터 p50 전 → 후 (ms) | 개선율 | 벡터 p95 전 → 후 (ms) | 개선율 "
             "| 하이브리드 p50 전 → 후 (ms) | 개선율 | HNSW 사용 전/후 | 결과 다른 질문 |",
             "|---|---:|---:|---|---|---:|---|---:|---|---:|---|---:|"]
    for r in results:
        lines.append(
            f"| {r['scale']} | {r['ratio'] * 100:g}% | {r['topK']} | {METHOD_NAMES.get(r['method'], r['method'])} | "
            f"{r['vectorP50Before']:,.1f} → {r['vectorP50After']:,.1f} | {fmt_percent(r['vectorP50ImprovementPercent'])} | "
            f"{r['vectorP95Before']:,.1f} → {r['vectorP95After']:,.1f} | {fmt_percent(r['vectorP95ImprovementPercent'])} | "
            f"{r['hybridP50Before']:,.1f} → {r['hybridP50After']:,.1f} | {fmt_percent(r['hybridP50ImprovementPercent'])} | "
            f"{r['hnswPlanBefore']}/{r['hnswPlanAfter']} | {r['resultMismatchQuestions']}/{r['questions']} |")
    return "\n".join(lines) + "\n"


# 범주형 색은 고정 순서로만 쓴다(dataviz 기준 팔레트 1~3번, 밝은/어두운 화면 각각 검증).
# 지연 그림: 1번(파랑)=수정 후, 2번(주황)=수정 전. 개선율 그림: 1번=벡터 단독, 3번(청록)=하이브리드(주황과 의미가 겹치지 않게).
SVG_STYLE = """<style>
.surface{fill:#fcfcfb}.ink{fill:#0b0b0b}.ink2{fill:#52514e}.grid{stroke:#e4e3de}.axis{stroke:#b9b8b1}
.ref{stroke:#8f8e87}.reffill{fill:#8f8e87}.ring{stroke:#fcfcfb}.link{stroke:#c9c8c1}
.l0{stroke:#2a78d6}.d0{fill:#2a78d6}.l1{stroke:#eb6834}.d1{fill:#eb6834}.d2{fill:#1baf7a}
@media (prefers-color-scheme: dark){.surface{fill:#1a1a19}.ink{fill:#f0efec}.ink2{fill:#c3c2b7}
.grid{stroke:#33332f}.axis{stroke:#5b5a54}.ref{stroke:#8a897f}.reffill{fill:#8a897f}.ring{stroke:#1a1a19}
.link{stroke:#4a4944}.l0{stroke:#3987e5}.d0{fill:#3987e5}.l1{stroke:#d95926}.d1{fill:#d95926}.d2{fill:#199e70}}
text{font-family:-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Noto Sans KR',sans-serif}
</style>"""


def text_width(text, size=12):
    """한글은 글자 크기만큼, 그 밖은 0.58배로 어림한 글자 폭."""
    return sum(size if ord(char) > 0x1100 else size * 0.58 for char in str(text))


def esc(text):
    return str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def svg_open(width, height, title, subtitle):
    return [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" width="{width}" height="{height}" '
            f'role="img" aria-label="{esc(title)}">', SVG_STYLE,
            f'<rect class="surface" width="{width}" height="{height}" rx="8"/>',
            f'<text class="ink" x="20" y="28" font-size="16" font-weight="600">{esc(title)}</text>',
            f'<text class="ink2" x="20" y="48" font-size="12">{esc(subtitle)}</text>']


def legend(out, items, y):
    """items = [(종류, 클래스, 글자)]. 종류는 dot·ring·tick·bar. 글자는 계열 색을 쓰지 않는다."""
    x = 20
    for kind, css, label in items:
        if kind == "dot":
            out.append(f'<circle class="{css} ring" cx="{x + 6}" cy="{y}" r="5" stroke-width="2"/>')
        elif kind == "tick":
            out.append(f'<line class="ref" x1="{x + 6}" y1="{y - 7}" x2="{x + 6}" y2="{y + 7}" stroke-width="2"/>')
        else:
            out.append(f'<rect class="{css}" x="{x}" y="{y - 6}" width="12" height="12" rx="2"/>')
        out.append(f'<text class="ink2" x="{x + 18}" y="{y + 4}" font-size="12">{esc(label)}</text>')
        x += 18 + text_width(label) + 24


def log_x(value, x0, width, low=1.0, high=10000.0):
    value = max(low, min(high, value))
    return x0 + (math.log10(value) - math.log10(low)) / (math.log10(high) - math.log10(low)) * width


def dumbbell_svg(path, title, subtitle, panels):
    """로그 축 위에 행마다 수정 전(주황)→수정 후(파랑) 지연을 잇고 수정 전 Custom Plan을 회색 눈금으로 둔다.

    panels = [(패널 제목, [(행 이름, 수정 전, 수정 후, 기준값)])]
    """
    label_width, plot_width, row_height = 70, 560, 44
    top = 112
    rows_total = sum(len(rows) for _, rows in panels)
    height = top + rows_total * row_height + len(panels) * 40 + 40
    width = 20 + label_width + plot_width + 90
    out = svg_open(width, height, title, subtitle)
    legend(out, [("dot", "d1", "수정 전 Generic Plan"), ("dot", "d0", "수정 후 Generic Plan"),
                 ("tick", "ref", "수정 전 Custom Plan(처음 5번)")], 72)
    x0 = 20 + label_width
    y = top
    for panel_title, rows in panels:
        out.append(f'<text class="ink" x="20" y="{y}" font-size="13" font-weight="600">{esc(panel_title)}</text>')
        y += 14
        plot_top, plot_bottom = y, y + len(rows) * row_height
        for tick in (1, 10, 100, 1000, 10000):
            x = log_x(tick, x0, plot_width)
            out.append(f'<line class="grid" x1="{x:.1f}" y1="{plot_top}" x2="{x:.1f}" y2="{plot_bottom}" stroke-width="1"/>')
            out.append(f'<text class="ink2" x="{x:.1f}" y="{plot_bottom + 14}" font-size="11" text-anchor="middle">'
                       f'{tick:,}ms</text>')
        for index, (label, before, after, reference) in enumerate(rows):
            cy = plot_top + index * row_height + row_height / 2
            xb, xa, xr = log_x(before, x0, plot_width), log_x(after, x0, plot_width), log_x(reference, x0, plot_width)
            out.append(f'<text class="ink2" x="20" y="{cy + 4:.1f}" font-size="12">{esc(label)}</text>')
            out.append(f'<line class="link" x1="{xa:.1f}" y1="{cy:.1f}" x2="{xb:.1f}" y2="{cy:.1f}" stroke-width="2"/>')
            out.append(f'<line class="ref" x1="{xr:.1f}" y1="{cy - 9:.1f}" x2="{xr:.1f}" y2="{cy + 9:.1f}" stroke-width="2">'
                       f'<title>수정 전 Custom Plan · {esc(label)}: {reference:,.1f}ms</title></line>')
            for css, x, value, name in (("d1", xb, before, "수정 전"), ("d0", xa, after, "수정 후")):
                out.append(f'<circle class="{css} ring" cx="{x:.1f}" cy="{cy:.1f}" r="5" stroke-width="2">'
                           f'<title>{name} Generic Plan · {esc(label)}: {value:,.1f}ms</title></circle>')
            # 값 글자는 두 점 바깥쪽에 둔다(수정 후는 왼쪽, 수정 전은 오른쪽).
            out.append(f'<text class="ink2" x="{xa - 9:.1f}" y="{cy - 9:.1f}" font-size="11" text-anchor="end">'
                       f'{after:,.1f}</text>')
            out.append(f'<text class="ink" x="{xb + 9:.1f}" y="{cy - 9:.1f}" font-size="11">{before:,.1f} '
                       f'→ {improvement(before, after):.1f}%↓</text>')
        y = plot_bottom + 40
    out.append(f'<text class="ink2" x="20" y="{height - 14}" font-size="11">x축: 서비스 호출 지연 p50 (로그 눈금). '
               '값 글자: 수정 후(왼쪽), 수정 전과 개선율(오른쪽)</text>')
    out.append("</svg>")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(out) + "\n")


def improvement_bars_svg(path, title, subtitle, rows, series_names, series_classes):
    """행마다 계열별 개선율(%) 가로 막대. rows = [(행 이름, [계열별 개선율])], 0~100% 선형 축."""
    label_width, plot_width, bar, gap = 70, 520, 14, 2
    group = len(series_names) * (bar + gap) + 18
    top = 104
    height = top + len(rows) * group + 50
    width = 20 + label_width + plot_width + 70
    out = svg_open(width, height, title, subtitle)
    legend(out, [("bar", css, name) for css, name in zip(series_classes, series_names)], 72)
    x0 = 20 + label_width
    plot_bottom = top + len(rows) * group
    for tick in range(0, 101, 20):
        x = x0 + tick / 100 * plot_width
        out.append(f'<line class="grid" x1="{x:.1f}" y1="{top - 6}" x2="{x:.1f}" y2="{plot_bottom}" stroke-width="1"/>')
        out.append(f'<text class="ink2" x="{x:.1f}" y="{plot_bottom + 16}" font-size="11" text-anchor="middle">{tick}%</text>')
    for row_index, (label, values) in enumerate(rows):
        y = top + row_index * group
        out.append(f'<text class="ink2" x="20" y="{y + group / 2 - 5:.1f}" font-size="12">{esc(label)}</text>')
        for series_index, value in enumerate(values):
            by = y + series_index * (bar + gap)
            length = max(0.0, value) / 100 * plot_width
            # 막대 끝만 둥글게(4px), 기준선 쪽은 각지게 그린다.
            out.append(f'<path class="{series_classes[series_index]}" d="M{x0},{by} h{max(0.0, length - 4):.1f} '
                       f'a4,4 0 0 1 4,4 v{bar - 8} a4,4 0 0 1 -4,4 h{-max(0.0, length - 4):.1f} z">'
                       f'<title>{esc(series_names[series_index])} · {esc(label)}: {value:.1f}%</title></path>')
            out.append(f'<text class="ink" x="{x0 + length + 6:.1f}" y="{by + bar - 3}" font-size="11">{value:.1f}%</text>')
    out.append(f'<text class="ink2" x="20" y="{height - 14}" font-size="11">x축: (수정 전 − 수정 후) ÷ 수정 전, p50 기준</text>')
    out.append("</svg>")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(out) + "\n")


def charts(results, output, prefix, scale="50000", top_k=10):
    """5만 청크·topK 10에서 현재 계획(Generic/Custom)의 전후를 그림으로 남긴다."""
    index = {(r["scale"], r["ratio"], r["topK"], r["method"]): r for r in results}
    ratios = sorted({r["ratio"] for r in results if r["scale"] == scale and r["topK"] == top_k}, reverse=True)
    panels = []
    for path_name, field in (("벡터 단독 검색", "vector"), ("하이브리드 검색 (기본 경로)", "hybrid")):
        rows = []
        for ratio in ratios:
            generic = index[(scale, ratio, top_k, "planner_default_generic")]
            custom = index[(scale, ratio, top_k, "planner_default")]
            rows.append((f"허용 {ratio * 100:g}%", generic[field + "P50Before"], generic[field + "P50After"],
                         custom[field + "P50Before"]))
        panels.append((path_name, rows))
    dumbbell_svg(os.path.join(output, prefix + "before-after-latency.svg"),
                 f"수정 전후 검색 지연 · Generic Plan ({int(scale):,} 청크, topK {top_k})",
                 "질문 30개 p50. 같은 SQL을 6번 넘게 실행해 Generic Plan이 쓰일 때의 지연이며, 수정 후에는 처음 5번 수준이 된다.",
                 panels)
    rows = []
    for ratio in ratios:
        generic = index[(scale, ratio, top_k, "planner_default_generic")]
        rows.append((f"허용 {ratio * 100:g}%", [generic["vectorP50ImprovementPercent"],
                                               generic["hybridP50ImprovementPercent"]]))
    improvement_bars_svg(os.path.join(output, prefix + "improvement-percent.svg"),
                         f"Generic Plan 지연 개선율 ({int(scale):,} 청크, topK {top_k})",
                         "질문 30개 p50. 허용 청크가 많을수록 행마다 하던 변환이 많아 개선 폭이 크다.",
                         rows, ["벡터 단독 검색", "하이브리드 검색"], ["d0", "d2"])


def main():
    if len(sys.argv) not in (4, 5):
        print(__doc__)
        sys.exit(2)
    before, after, output = load(sys.argv[1]), load(sys.argv[2]), sys.argv[3]
    prefix = sys.argv[4] if len(sys.argv) == 5 else ""
    results = compare(before, after)
    os.makedirs(output, exist_ok=True)
    with open(os.path.join(output, prefix + "comparison.csv"), "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(results[0].keys()))
        writer.writeheader()
        writer.writerows(results)
    with open(os.path.join(output, prefix + "comparison.md"), "w", encoding="utf-8") as handle:
        handle.write(markdown(results))
    charts(results, output, prefix)


if __name__ == "__main__":
    main()
