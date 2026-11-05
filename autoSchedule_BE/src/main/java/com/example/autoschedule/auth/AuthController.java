package com.example.autoschedule.auth;

import com.example.autoschedule.auth.AuthService.LoginResult;
import com.example.autoschedule.dto.UserDto;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthCookies cookies;

    public AuthController(AuthService authService, AuthCookies cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    /** 가입하면 바로 로그인된 상태가 된다(토큰은 쿠키). */
    @PostMapping("/signup")
    public ResponseEntity<UserDto.Response> signup(@Valid @RequestBody UserDto.SignupRequest request,
                                                   HttpServletResponse response) {
        LoginResult result = authService.signup(request);
        cookies.add(response, result.tokens());
        return ResponseEntity.status(HttpStatus.CREATED).body(result.user());
    }

    @PostMapping("/login")
    public UserDto.Response login(@Valid @RequestBody UserDto.LoginRequest request, HttpServletResponse response) {
        LoginResult result = authService.login(request);
        cookies.add(response, result.tokens());
        return result.user();
    }

    /** 리프레시 쿠키로 토큰을 다시 발급한다(쿠키 교체). */
    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(@CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken,
                                        HttpServletResponse response) {
        if (refreshToken == null) {
            throw new AuthException("다시 로그인해 주세요.");
        }
        cookies.add(response, authService.refresh(refreshToken));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken,
                                       HttpServletResponse response) {
        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        cookies.clear(response);
        return ResponseEntity.noContent().build();
    }
}
