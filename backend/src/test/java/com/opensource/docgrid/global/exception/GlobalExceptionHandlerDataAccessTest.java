package com.opensource.docgrid.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

import jakarta.servlet.http.HttpServletRequest;

/** Documents the current HTTP status mapping for a database connection failure. */
class GlobalExceptionHandlerDataAccessTest {

    @Test
    void databaseConnectionFailureIsReportedAsHttp500() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/ha-probe/writes");

        var response = new GlobalExceptionHandler().handleDataAccess(
            new DataAccessResourceFailureException("synthetic connection failure"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
