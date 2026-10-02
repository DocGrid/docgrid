package com.opensource.docgrid.domain.failover.controller;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.opensource.docgrid.domain.failover.service.command.HaProbeWriteService;

/** Checks the test-only HTTP contract without starting a database. */
@WebMvcTest(HaProbeWriteController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("ha-probe")
@TestPropertySource(properties = "docgrid.ha-probe.enabled=true")
class HaProbeWriteControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private HaProbeWriteService service;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void returnsCreatedAfterServiceWrite() throws Exception {
        mockMvc.perform(post("/api/ha-probe/writes")
                .contentType("application/json")
                .content("{\"runId\":\"run-1\",\"requestId\":\"run-1-v1-i1\"}"))
            .andExpect(status().isCreated());
        verify(service).write("run-1", "run-1-v1-i1");
    }

    @Test
    void rejectsUnsafeRequestIdentifier() throws Exception {
        mockMvc.perform(post("/api/ha-probe/writes")
                .contentType("application/json")
                .content("{\"runId\":\"run-1\",\"requestId\":\"=bad\"}"))
            .andExpect(status().isBadRequest());
    }
}
