package com.example.autoschedule.ai;

import com.example.autoschedule.exception.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

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
