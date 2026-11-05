package com.example.autoschedule.ai;

import java.util.List;
import java.util.Map;

/** LLM 호출 추상화. 파이프라인은 이 인터페이스만 알기 때문에 테스트에서 가짜로 바꿀 수 있다. */
public interface ScheduleAiClient {

    /** role 은 "user" 또는 "model". */
    record Turn(String role, String text) {
        public static Turn user(String text) {
            return new Turn("user", text);
        }

        public static Turn model(String text) {
            return new Turn("model", text);
        }
    }

    /** 응답 JSON 문자열(스키마 강제)을 돌려준다. 실패하면 ApiException. */
    String generate(String systemPrompt, List<Turn> turns, Map<String, Object> responseSchema);
}