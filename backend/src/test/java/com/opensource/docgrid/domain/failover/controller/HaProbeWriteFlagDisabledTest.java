package com.opensource.docgrid.domain.failover.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.opensource.docgrid.domain.failover.service.command.HaProbeWriteService;

/** Ensures the probe stays absent when only the test profile, not its explicit flag, is set. */
@WebMvcTest(HaProbeWriteController.class)
@ActiveProfiles("ha-probe")
@TestPropertySource(properties = "docgrid.ha-probe.enabled=false")
class HaProbeWriteFlagDisabledTest {

    @Autowired private RequestMappingHandlerMapping mappings;
    @MockitoBean private HaProbeWriteService service;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void disabledFlagHasNoProbeEndpoint() {
        assertFalse(mappings.getHandlerMethods().keySet().stream()
            .anyMatch(mapping -> mapping.toString().contains("/api/ha-probe/writes")));
    }
}
