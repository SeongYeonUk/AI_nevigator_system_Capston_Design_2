package com.rabbit.domain.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private Long roomId;
    private Long parentId;
    private String message;
    private Long activeNodeId;
}

