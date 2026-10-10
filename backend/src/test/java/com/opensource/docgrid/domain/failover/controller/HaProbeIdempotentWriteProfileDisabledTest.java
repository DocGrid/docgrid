package com.opensource.docgrid.domain.failover.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.opensource.docgrid.domain.failover.service.command.HaProbeIdempotentWriteService;

/** Checks that enabling the flag alone cannot expose the HA retry endpoint in a normal profile. */
@WebMvcTest(HaProbeIdempotentWriteController.class)
@TestPropertySource(properties = "docgrid.ha-probe.enabled=true")
class HaProbeIdempotentWriteProfileDisabledTest {

    @Autowired private RequestMappingHandlerMapping mappings;
    @MockitoBean private HaProbeIdempotentWriteService service;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void ordinaryProfileHasNoRetryEndpoint() {
        assertThat(mappings.getHandlerMethods().keySet().stream())
            .noneMatch(mapping -> mapping.toString().contains("/api/ha-probe/idempotent-writes"));
    }
}
