package com.example.autoschedule.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    static CorsConfiguration forApi(List<String> origins) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/stores/me");
        return SecurityConfig.corsSource(origins).getCorsConfiguration(req);
    }

    @Test
    void allowsOnlyConfiguredOriginsWithCredentials() {
        CorsConfiguration c = forApi(List.of(" https://app.example.com ", ""));
        assertThat(c.checkOrigin("https://app.example.com")).isEqualTo("https://app.example.com");
        assertThat(c.checkOrigin("https://evil.example")).isNull();
        assertThat(c.getAllowCredentials()).isTrue();
    }

    @Test
    void emptyMeansNoCrossOrigin() {
        assertThat(forApi(List.of()).checkOrigin("https://app.example.com")).isNull();
    }
}
