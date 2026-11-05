package com.example.autoschedule.ai;

import com.example.autoschedule.exception.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/**
 * Gemini generateContent 호출. 모델 목록은 앞에서부터 폴백(429/500/503/504/타임아웃이면 다음 모델).
 * API 키는 서버 환경변수(GEMINI_API_KEY)에서만 읽는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini")
public class GeminiClient implements ScheduleAiClient {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    /** 이 상태면 다음 모델로 넘어간다. 404 는 구글이 내린(또는 이 키로 못 쓰는) 모델이라 건너뛰어야 한다. */
    private static final Set<Integer> RETRYABLE = Set.of(404, 429, 500, 503, 504);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<Candidate> candidates) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(Content content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Content(List<Part> parts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Part(String text) {}

    private final RestClient restClient;
    private final String apiKey;
    private final List<String> models;

    public GeminiClient(@Value("${gemini.api-key:}") String apiKey,
                        @Value("${gemini.model:gemini-2.5-flash}") String models,
                        @Value("${gemini.timeout-seconds:150}") long timeoutSeconds) {
        this.apiKey = apiKey;
        this.models = Arrays.stream(models.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String generate(String systemPrompt, List<Turn> turns, Map<String, Object> responseSchema) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 서비스가 설정되지 않았습니다. 관리자에게 문의하세요.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        body.put("contents", turns.stream()
                .map(t -> Map.of("role", t.role(), "parts", List.of(Map.of("text", t.text()))))
                .toList());
        body.put("generationConfig", Map.of(
                "responseMimeType", "application/json",
                "responseSchema", responseSchema,
                "temperature", 0.3));

        String lastError = "알 수 없는 오류";
        for (String model : models) {
            try {
                Response res = restClient.post()
                        .uri(BASE + model + ":generateContent")
                        .header("x-goog-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Response.class);
                String text = extractText(res);
                if (text != null) return text;
                lastError = model + ": 빈 응답";
                log.warn("Gemini {} returned no text", model);
            } catch (HttpStatusCodeException e) {
                lastError = model + ": HTTP " + e.getStatusCode().value();
                log.warn("Gemini {} failed: {} {}", model, e.getStatusCode().value(), e.getResponseBodyAsString());
                if (!RETRYABLE.contains(e.getStatusCode().value())) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 호출에 실패했습니다. (" + e.getStatusCode().value() + ")");
                }
            } catch (ResourceAccessException e) {
                lastError = model + ": 연결 실패 또는 시간 초과";
                log.warn("Gemini {} timed out or unreachable: {}", model, e.getMessage());
            }
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 호출에 실패했습니다. 잠시 후 다시 시도하세요. (" + lastError + ")");
    }

    private static String extractText(Response res) {
        if (res == null || res.candidates() == null || res.candidates().isEmpty()) return null;
        Content c = res.candidates().get(0).content();
        if (c == null || c.parts() == null || c.parts().isEmpty()) return null;
        String text = c.parts().get(0).text();
        return text == null || text.isBlank() ? null : text;
    }
}