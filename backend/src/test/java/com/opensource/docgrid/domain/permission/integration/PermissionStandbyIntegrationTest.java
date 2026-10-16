package com.opensource.docgrid.domain.permission.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.opensource.docgrid.domain.permission.service.query.PrimaryDocumentPermissionQueryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/** 실제 물리 standby에 잘못 도착한 최종 인가가 권한을 추정하지 않고 닫히는지 검증한다. */
@Tag("permission-standby")
@SpringBootTest(classes = PermissionDurabilityIntegrationTest.Config.class, properties = {
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55440/postgres?ApplicationName=permission-standby-boundary",
    "spring.datasource.username=postgres", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false",
    "permission.revocation.synchronous-enabled=true", "management.server.port=0", "logging.level.root=ERROR"
})
class PermissionStandbyIntegrationTest {
    @Autowired PrimaryDocumentPermissionQueryService permissions;

    @Test
    @DisplayName("실제 standby에 도착한 최종 인가는 503으로 닫히고 권한 조회를 허용하지 않는다")
    void rejectsActualStandby() {
        assertThatThrownBy(() -> permissions.canReadDocument(-1L, -1L))
            .isInstanceOf(DocGridException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.DOCUMENT_PERMISSION_UNAVAILABLE);
        System.out.println("EVIDENCE " + OffsetDateTime.now() + " run=actual-standby authority_unavailable=503 content_returned=0");
    }
}
