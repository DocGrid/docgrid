package com.opensource.docgrid.domain.failover.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.failover.dto.request.HaProbeWriteRequest;
import com.opensource.docgrid.domain.failover.service.command.HaProbeWriteService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Exposes a JWT-protected HA write probe only under an explicit test profile and flag. */
@RestController
@RequestMapping("/api/ha-probe/writes")
@Profile("ha-probe")
@ConditionalOnProperty(name = "docgrid.ha-probe.enabled", havingValue = "true")
@RequiredArgsConstructor
public class HaProbeWriteController {

    private final HaProbeWriteService service;

    @PostMapping
    public ResponseEntity<Void> write(@Valid @RequestBody HaProbeWriteRequest request) {
        // 1. The shared SecurityFilterChain authenticates this non-/test path before invoking us.
        // 2. The service transaction commits before a 201 response is constructed.
        service.write(request.runId(), request.requestId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
