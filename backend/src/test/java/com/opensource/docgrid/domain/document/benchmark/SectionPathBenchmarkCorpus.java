package com.opensource.docgrid.domain.document.benchmark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

/**
 * Section 경로 임베딩 Benchmark의 결정적 문서 Corpus와 질의를 DOCX·Markdown 바이트로 만든다.
 *
 * <p>같은 서식의 약관 문서가 여러 개 있고 서비스 이름은 문서 제목에만 나오는 상황을 재현한다.
 * 본문은 모든 문서에서 같은 문장 틀을 쓰고 숫자만 다르므로 본문만으로는 어느 문서의 조항인지 구분할 수 없다.
 * 실제 문서 분포가 아니라 경로의 효과가 가장 잘 드러나는 조건이므로 결과는 상한에 가까운 값으로 해석한다.
 * 모델·DB·파일 I/O를 쓰지 않는 순수 생성 경계다.
 */
final class SectionPathBenchmarkCorpus {

    private static final List<String> DOMAINS = List.of("쇼핑몰", "구독서비스", "숙박예약", "중고거래");
    private static final String PREFACE = "이 약관은 서비스 이용에 관한 기본 사항을 정한다.";
    private static final String GROUP_INTRO = "본 조의 내용은 모든 이용자에게 동일하게 적용된다.";

    private static final List<SectionTemplate> TEMPLATES = List.of(
        new SectionTemplate("1. 환불 정책", "1.1 일반 환불", "일반 환불", "처리 수수료",
            "신청일로부터 %d일 이내에 요청하면 사유와 관계없이 환불할 수 있다. 처리 수수료는 %d원이며 결제 수단에 따라 "
                + "영업일 기준 3일에서 7일이 걸린다. 환불 금액은 결제 금액에서 수수료를 제외하고 계산한다."),
        new SectionTemplate("1. 환불 정책", "1.2 예외 환불", "예외 환불", "위약금",
            "천재지변이나 운영자의 귀책 사유가 있으면 기간 제한 없이 환불한다. 이 경우 위약금 %2$d원은 면제하되 "
                + "증빙 자료를 제출해야 한다. 접수 후 담당자가 확인하는 데 %1$d일이 걸린다."),
        new SectionTemplate("2. 교환 정책", "2.1 교환 신청", "교환 신청", "왕복 배송비",
            "교환은 수령일로부터 %d일 이내에 신청해야 한다. 고객 사유 교환은 왕복 배송비 %d원을 부담하고 운영자 사유 "
                + "교환은 전액 면제한다. 교환 접수 후 처리 결과를 문자로 안내한다."),
        new SectionTemplate("3. 지연 보상", "3.1 보상 기준", "보상 기준", "보상금",
            "처리가 약속한 기한보다 %d일 이상 늦어지면 보상한다. 보상금은 건당 %d원이며 다음 결제 시 자동으로 차감한다. "
                + "중복 보상은 지급하지 않는다."),
        new SectionTemplate("4. 회원 탈퇴", "4.1 탈퇴 처리", "탈퇴 처리", "정산 수수료",
            "탈퇴를 신청하면 %d일 동안 철회할 수 있다. 미사용 잔여 금액이 있으면 정산 수수료 %d원을 제외하고 돌려준다. "
                + "탈퇴가 완료되면 계정 정보는 복구할 수 없다."),
        new SectionTemplate("5. 개인정보", "5.1 보관 기간", "보관 기간", "열람 요청 수수료",
            "관련 법령에 따라 거래 기록은 %d일 동안 보관한 뒤 파기한다. 본인의 열람 요청은 접수 후 수수료 %d원을 받고 "
                + "처리한다. 보관 기간 중에는 목적 외로 이용하지 않는다.")
    );

    private SectionPathBenchmarkCorpus() {
    }

    /**
     * 서비스 4종 × 조항 6개로 구성한 약관 문서 4건을 만든다.
     */
    static List<BenchmarkDocument> documents() {
        List<BenchmarkDocument> documents = new ArrayList<>();
        for (int domainIndex = 0; domainIndex < DOMAINS.size(); domainIndex++) {
            List<Section> sections = new ArrayList<>();
            for (int templateIndex = 0; templateIndex < TEMPLATES.size(); templateIndex++) {
                SectionTemplate template = TEMPLATES.get(templateIndex);
                int days = 3 + domainIndex + templateIndex;
                int fee = 1_000 + domainIndex * 700 + templateIndex * 100;
                sections.add(new Section(
                    template.groupTitle(),
                    template.leafTitle(),
                    template.bodyTemplate().formatted(days, fee),
                    fee + "원"
                ));
            }
            String domain = DOMAINS.get(domainIndex);
            documents.add(new BenchmarkDocument("doc" + (domainIndex + 1), domain + " 이용약관", List.copyOf(sections)));
        }
        return List.copyOf(documents);
    }

    /**
     * 모든 조항마다 서비스 이름과 조항 이름으로 수치 값을 묻는 질의를 만든다. 정답은 해당 문서의 해당 조항뿐이다.
     */
    static List<QueryCase> queries() {
        List<QueryCase> queries = new ArrayList<>();
        List<BenchmarkDocument> documents = documents();
        for (int documentIndex = 0; documentIndex < documents.size(); documentIndex++) {
            BenchmarkDocument document = documents.get(documentIndex);
            for (int sectionIndex = 0; sectionIndex < document.sections().size(); sectionIndex++) {
                SectionTemplate template = TEMPLATES.get(sectionIndex);
                Section section = document.sections().get(sectionIndex);
                queries.add(new QueryCase(
                    "q" + (queries.size() + 1),
                    document.documentId(),
                    document.title() + "의 " + template.questionTopic() + "에서 " + template.factLabel() + "은 얼마인가?",
                    section.evidence()
                ));
            }
        }
        return List.copyOf(queries);
    }

    /**
     * 문서 제목은 Title, 조는 Heading1, 항은 Heading2 스타일인 DOCX 바이트를 만든다.
     */
    static byte[] toDocx(BenchmarkDocument document) {
        try (XWPFDocument docx = new XWPFDocument()) {
            paragraph(docx, "Title", document.title());
            paragraph(docx, null, PREFACE);
            String currentGroup = null;
            for (Section section : document.sections()) {
                if (!section.groupTitle().equals(currentGroup)) {
                    currentGroup = section.groupTitle();
                    paragraph(docx, "Heading1", currentGroup);
                    paragraph(docx, null, GROUP_INTRO);
                }
                paragraph(docx, "Heading2", section.leafTitle());
                paragraph(docx, null, section.body());
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            docx.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Benchmark DOCX를 만들지 못했습니다.", exception);
        }
    }

    /**
     * 문서 제목은 #, 조는 ##, 항은 ### 인 Markdown 바이트를 만든다.
     */
    static byte[] toMarkdown(BenchmarkDocument document) {
        StringBuilder markdown = new StringBuilder("# ").append(document.title()).append("\n\n")
            .append(PREFACE).append("\n\n");
        String currentGroup = null;
        for (Section section : document.sections()) {
            if (!section.groupTitle().equals(currentGroup)) {
                currentGroup = section.groupTitle();
                markdown.append("## ").append(currentGroup).append("\n\n").append(GROUP_INTRO).append("\n\n");
            }
            markdown.append("### ").append(section.leafTitle()).append("\n\n").append(section.body()).append("\n\n");
        }
        return markdown.toString().stripTrailing().getBytes(StandardCharsets.UTF_8);
    }

    private static void paragraph(XWPFDocument docx, String style, String text) {
        XWPFParagraph paragraph = docx.createParagraph();
        if (style != null) {
            paragraph.setStyle(style);
        }
        paragraph.createRun().setText(text);
    }

    /**
     * 문서 한 건의 제목과 조항 목록이다.
     */
    record BenchmarkDocument(String documentId, String title, List<Section> sections) {
    }

    /**
     * 조 제목, 항 제목, 본문과 정답 근거 문구를 가진 조항 한 개다.
     */
    record Section(String groupTitle, String leafTitle, String body, String evidence) {
    }

    /**
     * 질문과 정답 문서, 정답 청크가 반드시 포함해야 하는 근거 문구를 묶은 Ground Truth다.
     */
    record QueryCase(String queryId, String documentId, String question, String evidence) {
    }

    private record SectionTemplate(
        String groupTitle,
        String leafTitle,
        String questionTopic,
        String factLabel,
        String bodyTemplate
    ) {
    }
}
