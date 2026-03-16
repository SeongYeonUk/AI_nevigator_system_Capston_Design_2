package com.rabbit.global.config;

import com.rabbit.domain.chat.service.ConversationTreeAiService;
import com.rabbit.domain.chat.service.RabbitGuardService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
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
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-3-small")
                .build();
    }

    @Bean
    public RabbitGuardService rabbitGuardService(ChatLanguageModel chatLanguageModel) {
        return AiServices.builder(RabbitGuardService.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(50))
                .build();
    }

    @Bean
    public ConversationTreeAiService conversationTreeAiService(ChatLanguageModel chatLanguageModel) {
        return AiServices.builder(ConversationTreeAiService.class)
                .chatLanguageModel(chatLanguageModel)
                .build();
    }
}
