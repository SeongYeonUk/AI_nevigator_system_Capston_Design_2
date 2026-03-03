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
    private Long id;
    private Long parentId;
    private String content;
    private SenderRole sender;
    private int depth;
    private LocalDateTime createdAt;

    public static ChatHistoryResponse from(ChatMessage message) {
        return ChatHistoryResponse.builder()
                .id(message.getId())
                .parentId(message.getParent() != null ? message.getParent().getId() : null)
                .content(message.getContent())
                .sender(message.getSender())
                .depth(message.getDepth())
                .createdAt(message.getCreatedAt())
                .build();
    }
}