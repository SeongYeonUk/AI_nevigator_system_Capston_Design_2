package com.rabbit.domain.chat.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ConversationSummaryItemResponse {
    private String keyword;
    private List<String> details;
}
