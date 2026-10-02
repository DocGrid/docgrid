package com.opensource.docgrid.domain.failover.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Identifies one external-ledger HA attempt without carrying payload or credentials. */
public record HaProbeWriteRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}") String runId,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}") String requestId
) {
}
