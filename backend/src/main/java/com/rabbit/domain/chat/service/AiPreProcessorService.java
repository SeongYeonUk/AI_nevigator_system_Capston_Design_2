package com.rabbit.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbit.domain.chat.dto.TopicExtractionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiPreProcessorService {

    private final ConversationTreeAiService aiService;
    private final ObjectMapper objectMapper;

    public TopicExtractionResponse parseUserInput(String userRawInput) {
        try {
            // 1. LangChain4j 인터페이스를 통해 찰떡같이 JSON 문자열을 받아옴
            String jsonText = aiService.extractTopics(userRawInput);

            // 2. 혹시 모를 마크다운 백틱(```json ... ```) 제거
            String cleanedJson = jsonText.replaceAll("(?s)```json\\s*(.*?)\\s*```", "$1").trim();

            // 3. JSON 문자열을 Java 레코드 객체로 변환
            return objectMapper.readValue(cleanedJson, TopicExtractionResponse.class);

        } catch (Exception e) {
            // AI가 이상한 말을 뱉거나 파싱 에러가 났을 때의 방어 로직 (최후의 보루)
            return new TopicExtractionResponse("기타 학습", List.of(userRawInput));
        }
    }
}
