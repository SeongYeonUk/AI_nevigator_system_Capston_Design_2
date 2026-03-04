package com.rabbit.domain.chat.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ConversationTreeNodeResponse {
    private Long id;
    private Long parentId;
    private String title;
    private String userQuestion;
    private String aiAnswer;
    private int depth;
    private LocalDateTime createdAt;
}
