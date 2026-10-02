package com.opensource.docgrid.domain.failover.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.opensource.docgrid.domain.failover.service.command.HaProbeWriteService;

/** Proves that ordinary profiles cannot register the HA probe endpoint. */
@WebMvcTest(HaProbeWriteController.class)
@AutoConfigureMockMvc(addFilters = false)
class HaProbeWriteDisabledTest {

    @Autowired private RequestMappingHandlerMapping mappings;
    @MockitoBean private HaProbeWriteService service;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void defaultProfileHasNoProbeEndpoint() throws Exception {
        assertFalse(mappings.getHandlerMethods().keySet().stream()
            .anyMatch(mapping -> mapping.toString().contains("/api/ha-probe/writes")));
    }
}
