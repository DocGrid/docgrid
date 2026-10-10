package com.opensource.docgrid.domain.failover.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.failover.dto.request.HaProbeIdempotentWriteRequest;
import com.opensource.docgrid.domain.failover.service.command.HaProbeIdempotentWriteService;
import com.opensource.docgrid.global.diagnostics.HaProbeDiagnosticContext;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Exposes the ADMIN-only, profile-gated idempotent HA probe for retry demonstrations.
 *
 * <p>It does not change the original duplicate-observable probe or retry failed writes itself.
 */
@RestController
@RequestMapping("/api/ha-probe/idempotent-writes")
@Profile("ha-probe")
@ConditionalOnProperty(name = "docgrid.ha-probe.enabled", havingValue = "true")
@RequiredArgsConstructor
public class HaProbeIdempotentWriteController {

    private final HaProbeIdempotentWriteService service;

    @PostMapping
    public ResponseEntity<Void> write(@Valid @RequestBody HaProbeIdempotentWriteRequest request) {
        // 1. The existing security chain authenticates ADMIN before this controller runs.
        HaProbeDiagnosticContext.phase("TX_BEGIN");
        // 2. The service transaction commits before a success or conflict response is sent.
        var outcome = service.write(request.runId(), request.requestId(), request.payload());
        return switch (outcome) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).build();
            case REPLAYED -> ResponseEntity.ok().build();
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }
}
