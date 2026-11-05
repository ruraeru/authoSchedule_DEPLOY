package com.example.autoschedule.auth;

import com.example.autoschedule.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이메일별 로그인 실패 제한: 15분 안에 5번 틀리면 그 15분이 끝날 때까지 로그인을 막는다(429).
 * shortcut: 서버 메모리에만 있어 재시작하면 초기화되고 인스턴스끼리 공유되지 않는다. 여러 대로 늘리면 Redis 등으로 옮긴다.
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int PRUNE_THRESHOLD = 10_000;

    private record Window(int failures, Instant start) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    public void checkAllowed(String email) {
        Window w = windows.get(email);
        if (w != null && !expired(w) && w.failures() >= MAX_FAILURES) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.");
        }
    }

    public void recordFailure(String email) {
        if (windows.size() > PRUNE_THRESHOLD) windows.values().removeIf(this::expired); // 없는 이메일로 맵을 키우는 것 방지
        windows.compute(email, (k, w) -> w == null || expired(w)
                ? new Window(1, clock.instant()) : new Window(w.failures() + 1, w.start()));
    }

    public void reset(String email) {
        windows.remove(email);
    }

    private boolean expired(Window w) {
        return !clock.instant().isBefore(w.start().plus(WINDOW));
    }
}
