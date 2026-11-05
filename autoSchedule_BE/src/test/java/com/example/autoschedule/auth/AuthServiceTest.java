package com.example.autoschedule.auth;

import com.example.autoschedule.auth.jwt.JwtProvider;
import com.example.autoschedule.dto.UserDto;
import com.example.autoschedule.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    @Test
    void unknownEmailStillRunsPasswordCheckAndCountsFailure() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        LoginThrottle throttle = mock(LoginThrottle.class);
        when(encoder.encode(anyString())).thenReturn("dummy-hash");
        when(users.findByEmail("nobody@x")).thenReturn(Optional.empty());
        AuthService service = new AuthService(users, mock(RefreshTokenRepository.class), mock(JwtProvider.class),
                encoder, 60, throttle);

        UserDto.LoginRequest req = new UserDto.LoginRequest();
        req.setEmail(" Nobody@X ");
        req.setPassword("pw");

        assertThatThrownBy(() -> service.login(req)).isInstanceOf(AuthException.class);
        verify(encoder).matches("pw", "dummy-hash");
        verify(throttle).checkAllowed("nobody@x");
        verify(throttle).recordFailure(eq("nobody@x"));
    }
}
