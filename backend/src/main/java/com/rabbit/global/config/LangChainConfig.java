package com.rabbit.global.config;

import com.rabbit.domain.chat.service.RabbitGuardService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangChainConfig {

    @Value("${OPENAI_API_KEY}")
    private String apiKey;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .build();
    }

    @Bean
    public RabbitGuardService rabbitGuardService(ChatLanguageModel chatLanguageModel) {
        // 인터페이스와 모델을 직접 연결해줍니다.
        return AiServices.builder(RabbitGuardService.class)
                .chatLanguageModel(chatLanguageModel)
                // 대화 메모리 설정: 각 ID(방 번호)마다 최근 50개의 메시지를 기억
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(50))
                .build();
    }
}