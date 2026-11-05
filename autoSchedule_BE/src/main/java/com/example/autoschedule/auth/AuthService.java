package com.example.autoschedule.auth;

import com.example.autoschedule.auth.jwt.JwtProvider;
import com.example.autoschedule.dto.UserDto;
import com.example.autoschedule.exception.ApiException;
import com.example.autoschedule.entity.User;
import com.example.autoschedule.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthService {

    public record Tokens(String accessToken, String refreshToken, String tokenType, long expiresIn) {}

    public record LoginResult(UserDto.Response user, Tokens tokens) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;
    private final Duration refreshTtl;
    private final LoginThrottle throttle;
    /** 없는 이메일에도 BCrypt 비교를 한 번 해서, 응답 시간으로 가입 여부가 드러나지 않게 한다. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
                       JwtProvider jwtProvider, PasswordEncoder passwordEncoder,
                       @Value("${jwt.refresh-ttl-seconds}") long refreshTtlSeconds, LoginThrottle throttle) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.refreshTtl = Duration.ofSeconds(refreshTtlSeconds);
        this.throttle = throttle;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public LoginResult signup(UserDto.SignupRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("이미 가입된 이메일입니다.");
        }
        User user = new User();
        user.setName(request.getName().trim());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        try {
            User saved = userRepository.saveAndFlush(user);
            return new LoginResult(UserDto.Response.from(saved), issue(saved.getId()));
        } catch (DataIntegrityViolationException e) {
            // 동시 가입으로 existsByEmail 검사를 통과한 경우 (unique 제약)
            throw ApiException.conflict("이미 가입된 이메일입니다.");
        }
    }

    @Transactional
    public LoginResult login(UserDto.LoginRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        throttle.checkAllowed(email);
        User user = userRepository.findByEmail(email).orElse(null);
        boolean ok = passwordEncoder.matches(request.getPassword(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !ok) {
            throttle.recordFailure(email);
            throw new AuthException("이메일 또는 비밀번호가 맞지 않습니다.");
        }
        throttle.reset(email);
        return new LoginResult(UserDto.Response.from(user), issue(user.getId()));
    }

    /** Rotates the refresh token: the presented one is consumed and a new pair is issued. */
    @Transactional(noRollbackFor = AuthException.class)
    public Tokens refresh(String refreshToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash(refreshToken))
                .orElseThrow(() -> new AuthException("다시 로그인해 주세요."));
        refreshTokenRepository.delete(stored);
        if (stored.isExpired()) {
            throw new AuthException("다시 로그인해 주세요.");
        }
        return issue(stored.getUserId());
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.deleteByTokenHash(hash(refreshToken));
    }

    /** 로그아웃하지 않고 만료된 refresh token 은 남으므로 매일 새벽에 지운다. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeExpiredRefreshTokens() {
        refreshTokenRepository.deleteExpired(Instant.now());
    }

    private Tokens issue(Long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokenRepository.save(new RefreshToken(userId, hash(refreshToken), Instant.now().plus(refreshTtl)));
        return new Tokens(jwtProvider.createAccessToken(userId), refreshToken, "Bearer", jwtProvider.accessTtlSeconds());
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
