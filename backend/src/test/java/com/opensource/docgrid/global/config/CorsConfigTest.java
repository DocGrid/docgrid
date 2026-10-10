package com.opensource.docgrid.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Verifies the exact browser origin shared by HTTP CORS and the WebSocket endpoint. */
class CorsConfigTest {

    @Test
    void vercelProductionOriginIsAllowed() {
        CorsConfig config = new CorsConfig();
        var cors = config.corsConfigurationSource().getCorsConfiguration(new MockHttpServletRequest());

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins()).contains("https://zippy-lute-8okpbzh.vercel.app");
        assertThat(CorsConfig.ALLOWED_ORIGINS).contains("https://zippy-lute-8okpbzh.vercel.app");
    }
}
