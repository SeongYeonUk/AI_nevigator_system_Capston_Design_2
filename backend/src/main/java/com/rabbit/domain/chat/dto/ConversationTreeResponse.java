package com.rabbit.domain.chat.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ConversationTreeResponse {
    private Long roomId;
    private String level1Topic;
    private List<String> level2Topics;
    private int totalNodes;
    private boolean processing;
    private List<ConversationTreeNodeResponse> nodes;
}
