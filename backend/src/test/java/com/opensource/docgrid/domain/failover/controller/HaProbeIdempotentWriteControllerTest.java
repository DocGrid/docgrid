package com.opensource.docgrid.domain.failover.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.opensource.docgrid.domain.failover.service.command.HaProbeIdempotentWriteService;

/** Verifies the test-only HTTP result codes without a database or a live fault. */
@WebMvcTest(HaProbeIdempotentWriteController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("ha-probe")
@TestPropertySource(properties = "docgrid.ha-probe.enabled=true")
class HaProbeIdempotentWriteControllerTest {

    private static final String BODY = """
        {"runId":"ha123run","requestId":"ha123run-v1-i1","payload":"payload-1"}
        """;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private HaProbeIdempotentWriteService service;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void firstWriteReturns201() throws Exception {
        when(service.write("ha123run", "ha123run-v1-i1", "payload-1"))
            .thenReturn(HaProbeIdempotentWriteService.Outcome.CREATED);
        postBody(BODY, 201);
    }

    @Test
    void samePayloadReplayReturns200() throws Exception {
        when(service.write("ha123run", "ha123run-v1-i1", "payload-1"))
            .thenReturn(HaProbeIdempotentWriteService.Outcome.REPLAYED);
        postBody(BODY, 200);
    }

    @Test
    void differentPayloadReturns409() throws Exception {
        when(service.write("ha123run", "ha123run-v1-i1", "payload-1"))
            .thenReturn(HaProbeIdempotentWriteService.Outcome.CONFLICT);
        postBody(BODY, 409);
    }

    @Test
    void malformedPayloadReturns400() throws Exception {
        postBody("""
            {"runId":"ha123run","requestId":"ha123run-v1-i1","payload":"=unsafe"}
            """, 400);
    }

    @Test
    void databaseFailureDoesNotBecomeFalseReplay() throws Exception {
        doThrow(new DataAccessResourceFailureException("private database address"))
            .when(service).write("ha123run", "ha123run-v1-i1", "payload-1");
        postBody(BODY, 500);
    }

    private void postBody(String body, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/ha-probe/idempotent-writes")
                .contentType("application/json")
                .content(body))
            .andExpect(status().is(expectedStatus));
    }
}
