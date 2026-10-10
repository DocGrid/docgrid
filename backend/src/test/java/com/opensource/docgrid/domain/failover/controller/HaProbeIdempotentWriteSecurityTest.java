package com.opensource.docgrid.domain.failover.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.opensource.docgrid.domain.failover.service.command.HaProbeIdempotentWriteService;

/** Ensures an ordinary authenticated user cannot access the retry probe. */
@WebMvcTest(HaProbeIdempotentWriteController.class)
@ActiveProfiles("ha-probe")
@TestPropertySource(properties = "docgrid.ha-probe.enabled=true")
class HaProbeIdempotentWriteSecurityTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private HaProbeIdempotentWriteService service;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void nonAdminCannotWrite() throws Exception {
        var user = UsernamePasswordAuthenticationToken.authenticated("test-user", null, List.of());
        mockMvc.perform(post("/api/ha-probe/idempotent-writes")
                .with(authentication(user))
                .contentType("application/json")
                .content("""
                    {"runId":"ha123run","requestId":"ha123run-v1-i1","payload":"payload-1"}
                    """))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
