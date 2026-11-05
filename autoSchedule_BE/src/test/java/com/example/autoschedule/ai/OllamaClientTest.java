package com.example.autoschedule.ai;

import com.example.autoschedule.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaClientTest {

    final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @SuppressWarnings("unchecked")
    void geminiSchemaBecomesJsonSchema() {
        Map<String, Object> schema = (Map<String, Object>) OllamaClient.toJsonSchema(AiPromptBuilder.responseSchema());

        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("required")).isEqualTo(List.of("shifts", "notes"));
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> shifts = (Map<String, Object>) props.get("shifts");
        assertThat(shifts.get("type")).isEqualTo("array");
        Map<String, Object> shiftProps = (Map<String, Object>) ((Map<String, Object>) shifts.get("items")).get("properties");
        assertThat(shiftProps.get("staffId")).isEqualTo(Map.of("type", "integer"));
        assertThat(shiftProps.get("partId")).isEqualTo(Map.of("type", List.of("string", "null")));
    }

    @Test
    void joinsStreamedContent() {
        String ndjson = """
                {"message":{"role":"assistant","content":"{\\"shifts\\":"},"done":false}
                {"message":{"role":"assistant","content":"[],\\"notes\\":\\"ok\\"}"},"done":false}

                {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}
                """;
        assertThat(OllamaClient.joinStream(ndjson, mapper)).isEqualTo("{\"shifts\":[],\"notes\":\"ok\"}");
    }

    /** Ollama 는 application/x-ndjson 을 charset 없이 보낸다. 한글이 ISO-8859-1로 깨지면 안 된다. */
    @Test
    void decodesKoreanAsUtf8WithoutCharset() throws Exception {
        String line = "{\"message\":{\"content\":\"{\\\"notes\\\":\\\"주 15시간 이내로 배치했습니다\\\"}\"},\"done\":true}\n";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", ex -> {
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            ex.sendResponseHeaders(200, bytes.length);
            try (var out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            OllamaClient client = new OllamaClient(mapper, "http://127.0.0.1:" + server.getAddress().getPort(),
                    "m", "", 1024, 10);
            assertThat(client.generate("sys", List.of(ScheduleAiClient.Turn.user("u")), AiPromptBuilder.responseSchema()))
                    .isEqualTo("{\"notes\":\"주 15시간 이내로 배치했습니다\"}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void errorChunkOrEmptyResponseFails() {
        assertThatThrownBy(() -> OllamaClient.joinStream("{\"error\":\"model not found\"}\n", mapper))
                .isInstanceOf(ApiException.class).hasMessageContaining("model not found");
        assertThatThrownBy(() -> OllamaClient.joinStream("{\"done\":true,\"done_reason\":\"length\"}\n", mapper))
                .isInstanceOf(ApiException.class).hasMessageContaining("length");
        assertThatThrownBy(() -> OllamaClient.joinStream("not json\n", mapper))
                .isInstanceOf(ApiException.class);
    }
}
