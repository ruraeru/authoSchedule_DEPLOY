package com.example.autoschedule.ai;

import com.example.autoschedule.exception.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ollama 호환 서버(LLM_BASE_URL)의 /api/chat 호출. 프론트 api/generate.ts 의 generateJson 과 같은 방식이다.
 * stream:true 로 받아(NDJSON) 응답이 오래 걸려도 중간 프록시가 연결을 끊지 않게 하고, message.content 조각을 이어 붙인다.
 * ai.provider=gemini 이면 이 대신 {@link GeminiClient} 를 쓴다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaClient implements ScheduleAiClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Chunk(Message message, Boolean done, @JsonProperty("done_reason") String doneReason, String error) {}

    private final RestClient restClient;
    private final JsonMapper mapper;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final int numCtx;

    public OllamaClient(JsonMapper mapper,
                        @Value("${llm.base-url:https://ai.ruraeru.xyz}") String baseUrl,
                        @Value("${llm.model:qwen3.5:9b}") String model,
                        @Value("${llm.api-key:}") String apiKey,
                        @Value("${llm.num-ctx:16384}") int numCtx,
                        @Value("${llm.timeout-seconds:300}") long timeoutSeconds) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        // 환경변수에 붙여넣을 때 섞이는 공백·따옴표·"Bearer " 를 걷어낸다
        this.apiKey = apiKey.trim().replaceAll("^[\"']|[\"']$", "").replaceFirst("(?i)^Bearer\\s+", "");
        this.numCtx = numCtx;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String generate(String systemPrompt, List<Turn> turns, Map<String, Object> responseSchema) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (Turn t : turns) {
            messages.add(Map.of("role", "model".equals(t.role()) ? "assistant" : "user", "content", t.text()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("format", toJsonSchema(responseSchema));
        body.put("think", false);
        body.put("stream", true);
        body.put("options", Map.of("num_ctx", numCtx, "temperature", 0.3));
        body.put("keep_alive", "30m");

        String ndjson;
        try {
            RestClient.RequestBodySpec req = restClient.post()
                    .uri(baseUrl + "/api/chat")
                    .contentType(MediaType.APPLICATION_JSON);
            if (!apiKey.isEmpty()) req.header("Authorization", "Bearer " + apiKey);
            // Ollama는 application/x-ndjson 을 charset 없이 보낸다. String 으로 받으면 ISO-8859-1로 읽혀 한글이 깨지므로 바이트로 받아 UTF-8로 푼다
            byte[] raw = req.body(body).retrieve().body(byte[].class);
            ndjson = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            log.warn("LLM {} failed: {} {}", model, status, e.getResponseBodyAsString());
            if (status == 401 || status == 403) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 서버가 인증을 거절했습니다(" + status + "). LLM_API_KEY 를 확인하세요.");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 호출에 실패했습니다. (" + status + ")");
        } catch (ResourceAccessException e) {
            log.warn("LLM {} unreachable or timed out: {}", baseUrl, e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 서버에 연결하지 못했거나 시간이 초과됐습니다. 잠시 후 다시 시도하세요.");
        }
        return joinStream(ndjson, mapper);
    }

    /** NDJSON 응답의 message.content 를 이어 붙인다. 빈 응답·오류 줄은 ApiException. */
    static String joinStream(String ndjson, JsonMapper mapper) {
        StringBuilder content = new StringBuilder();
        String reason = null;
        for (String line : ndjson == null ? new String[0] : ndjson.split("\n")) {
            if (line.isBlank()) continue;
            Chunk chunk;
            try {
                chunk = mapper.readValue(line, Chunk.class);
            } catch (JacksonException e) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 응답 형식이 올바르지 않습니다.");
            }
            if (chunk.error() != null) {
                log.warn("LLM error chunk: {}", chunk.error());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 오류: " + chunk.error());
            }
            if (chunk.message() != null && chunk.message().content() != null) content.append(chunk.message().content());
            if (Boolean.TRUE.equals(chunk.done())) reason = chunk.doneReason();
        }
        if (content.isEmpty() || content.toString().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI가 빈 응답을 보냈습니다. (done_reason " + (reason == null ? "없음" : reason) + ")");
        }
        // JSON 이 아니면(잘림 등) 파이프라인이 파싱 실패로 보고 보정 요청을 한다
        return content.toString();
    }

    /**
     * AiPromptBuilder.responseSchema() 는 Gemini 형식(type 대문자, nullable)이다. Ollama format 은 JSON Schema 라
     * type 을 소문자로 바꾸고 nullable:true 는 ["type","null"] 로 바꾼다.
     */
    @SuppressWarnings("unchecked")
    static Object toJsonSchema(Object node) {
        if (node instanceof List<?> list) return list.stream().map(OllamaClient::toJsonSchema).toList();
        if (!(node instanceof Map<?, ?> map)) return node;
        Map<String, Object> out = new LinkedHashMap<>();
        boolean nullable = Boolean.TRUE.equals(map.get("nullable"));
        ((Map<String, Object>) map).forEach((k, v) -> {
            if ("nullable".equals(k)) return;
            if ("type".equals(k) && v instanceof String t) {
                String type = t.toLowerCase();
                out.put(k, nullable ? List.of(type, "null") : type);
            } else if ("required".equals(k)) {
                out.put(k, v);
            } else {
                out.put(k, toJsonSchema(v));
            }
        });
        return out;
    }
}
