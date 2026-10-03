package com.opensource.docgrid.domain.failover.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.opensource.docgrid.domain.failover.service.command.HaProbeWriteService;
import com.opensource.docgrid.global.diagnostics.HaProbeDiagnosticContext;

/** Checks the test-only HTTP contract without starting a database. */
@WebMvcTest(HaProbeWriteController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("ha-probe")
@TestPropertySource(properties = "docgrid.ha-probe.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
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

    @Test
    void logsOnlySafeFailureFieldsAndReturnsGeneric500(CapturedOutput output) throws Exception {
        doThrow(new DataAccessResourceFailureException(
            "private host and password", new SQLException("private connection URL", "08006")))
            .when(service).write("ha418test1", "ha418test1-v1-i1");
        HaProbeDiagnosticContext.begin("ha418test1", "ha418test1-v1-i1");
        try {
            mockMvc.perform(post("/api/ha-probe/writes")
                    .contentType("application/json")
                    .content("{\"runId\":\"ha418test1\",\"requestId\":\"ha418test1-v1-i1\"}"))
                .andExpect(status().isInternalServerError());
        } finally {
            HaProbeDiagnosticContext.clear();
        }

        assertThat(output.getAll()).contains(
            "HA_PROBE_EXCEPTION run=ha418test1 request=ha418test1-v1-i1 phase=TX_BEGIN");
        assertThat(output.getAll()).contains("type=DataAccessResourceFailureException sqlstate=08006");
        assertThat(output.getAll()).doesNotContain("private host and password", "private connection URL");
    }

    @Test
    void preservesConflictForDuplicateProbeRow(CapturedOutput output) throws Exception {
        doThrow(new DataIntegrityViolationException("private DB constraint name"))
            .when(service).write("ha418test1", "ha418test1-v1-i2");
        HaProbeDiagnosticContext.begin("ha418test1", "ha418test1-v1-i2");
        try {
            mockMvc.perform(post("/api/ha-probe/writes")
                    .contentType("application/json")
                    .content("{\"runId\":\"ha418test1\",\"requestId\":\"ha418test1-v1-i2\"}"))
                .andExpect(status().isConflict());
        } finally {
            HaProbeDiagnosticContext.clear();
        }

        assertThat(output.getAll()).contains("status=409");
        assertThat(output.getAll()).doesNotContain("private DB constraint name");
    }
}
