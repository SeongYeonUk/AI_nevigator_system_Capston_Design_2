package com.rabbit.domain.chat.dto;

import com.rabbit.domain.chat.enums.SenderRole;
import com.rabbit.domain.chat.entity.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;
@Getter
@AllArgsConstructor
@Builder
public class ChatHistoryResponse {
    private String content;
    private SenderRole sender;
    private LocalDateTime createdAt;

    public static ChatHistoryResponse from(ChatMessage message) {
        return ChatHistoryResponse.builder()
                .content(message.getContent())
                .sender(message.getSender())
                .createdAt(message.getCreatedAt())
                .build();
    }
}