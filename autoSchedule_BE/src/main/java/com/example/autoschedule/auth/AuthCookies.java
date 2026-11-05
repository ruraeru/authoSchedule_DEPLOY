package com.example.autoschedule.auth;

import com.example.autoschedule.auth.AuthService.Tokens;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 프론트가 credentials: 'include' 로 호출하므로 토큰은 HttpOnly 쿠키로 주고받는다. */
@Component
public class AuthCookies {

    public static final String ACCESS = "access_token";
    public static final String REFRESH = "refresh_token";
    private static final String REFRESH_PATH = "/api/auth";

    private final boolean secure;
    private final Duration refreshTtl;

    public AuthCookies(@Value("${auth.cookie-secure:false}") boolean secure,
                       @Value("${jwt.refresh-ttl-seconds}") long refreshTtlSeconds) {
        this.secure = secure;
        this.refreshTtl = Duration.ofSeconds(refreshTtlSeconds);
    }

    public void add(HttpServletResponse response, Tokens tokens) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                build(ACCESS, tokens.accessToken(), "/", Duration.ofSeconds(tokens.expiresIn())).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, build(REFRESH, tokens.refreshToken(), REFRESH_PATH, refreshTtl).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(ACCESS, "", "/", Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, build(REFRESH, "", REFRESH_PATH, Duration.ZERO).toString());
    }

    private ResponseCookie build(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
