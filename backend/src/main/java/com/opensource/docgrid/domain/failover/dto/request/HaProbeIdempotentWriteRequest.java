package com.opensource.docgrid.domain.failover.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Carries one synthetic HA retry request and its stable payload.
 *
 * <p>This test-only input contains no document content or credentials and is not a product-wide
 * idempotency contract.
 */
public record HaProbeIdempotentWriteRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}") String runId,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}") String requestId,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}") String payload
) {
}
