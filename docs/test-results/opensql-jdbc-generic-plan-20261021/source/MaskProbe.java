import java.util.StringJoiner;

/** 3,559개 허용 문서의 긴 EXPLAIN 배열을 마스킹하는 정규식만 로컬에서 검증한다. DB·인증 접근은 없다. */
public final class MaskProbe {
    public static void main(String[] args) {
        // 1. 실제 EXPLAIN과 같은 길이의 매개변수 배열을 만들되 업무 데이터는 쓰지 않는다.
        StringJoiner values = new StringJoiner(", ", "ARRAY[", "]");
        for (int i=1;i<=3559;i++) values.add("$"+i);
        String line=values.toString();boolean overflow=false;
        // 2. 원래 표현의 실패를 잡아 원문 스택 없이 오류 분류만 남긴다.
        try { line.replaceAll("ARRAY\\[\\$[0-9]+(, \\$[0-9]+)+\\]", "ARRAY[<목록>]"); }
        catch (StackOverflowError e) { overflow=true; }
        // 3. 문자 클래스 기반 표현은 반복 그룹 재귀 없이 같은 배열을 가린다.
        boolean fixed=line.replaceAll("ARRAY\\[[^\\]]*\\]", "ARRAY[<목록>]").equals("ARRAY[<목록>]");
        System.out.println("{\"이벤트\":\"긴 배열 마스킹\",\"매개변수수\":3559,\"기존_StackOverflowError\":"+overflow+",\"수정_완료\":"+fixed+"}");
        if(!fixed)System.exit(1);
    }
}
