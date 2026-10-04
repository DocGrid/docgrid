package com.opensource.docgrid.domain.document.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 문서를 앞에서부터 읽으며 현재 위치의 Heading 계층(상위→하위)을 유지한다.
 *
 * <p>새 Heading이 들어오면 같거나 깊은 레벨의 이전 Heading을 걷어내고 자신을 추가한다. Heading 판정과
 * 레벨 해석은 각 Parser가 맡고, 이 클래스는 계층 유지와 Metadata JSON 변환만 담당한다.
 * 상태를 가지므로 문서 한 건을 파싱하는 동안에만 쓰고 공유하지 않는다.
 */
final class HeadingTrail {

    private final List<Entry> entries = new ArrayList<>();

    /**
     * 새 Heading을 추가하고 이보다 같거나 깊은 레벨의 이전 Heading을 제거한다.
     */
    void push(int level, String text) {
        while (!entries.isEmpty() && entries.get(entries.size() - 1).level() >= level) {
            entries.remove(entries.size() - 1);
        }
        entries.add(new Entry(level, text));
    }

    /**
     * 현재 계층을 Chunk Metadata JSON으로 만든다. 첫 Heading 이전 본문처럼 경로가 없으면 null이다.
     */
    String metadataJson() {
        if (entries.isEmpty()) {
            return null;
        }
        List<String> headings = entries.stream().map(Entry::text).toList();
        return SectionPath.toMetadataJson(headings, entries.get(entries.size() - 1).level());
    }

    private record Entry(int level, String text) {
    }
}
