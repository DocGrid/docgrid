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

import com.opensource.docgrid.domain.failover.service.command.HaProbeWriteService;

/** Verifies that enabling the probe never lets a non-ADMIN user write test rows. */
@WebMvcTest(HaProbeWriteController.class)
@ActiveProfiles("ha-probe")
@TestPropertySource(properties = "docgrid.ha-probe.enabled=true")
class HaProbeWriteSecurityTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private HaProbeWriteService service;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void authenticatedUserWithoutAdminRoleIsForbidden() throws Exception {
        var user = UsernamePasswordAuthenticationToken.authenticated("test-user", null, List.of());
        mockMvc.perform(post("/api/ha-probe/writes")
                .with(authentication(user))
                .contentType("application/json")
                .content("{\"runId\":\"run-1\",\"requestId\":\"run-1-v1-i1\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
