package com.rabbit.domain.chat.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ChatResponse {
    private String answer;
    private Long newNodeId;
    private Long resolvedParentId;
    private String nodeTitle;
    private String level1Topic;
    private String level2Topic;
    private Integer depth;

    @Builder
    public ChatResponse(
            String answer,
            Long newNodeId,
            Long resolvedParentId,
            String nodeTitle,
            String level1Topic,
            String level2Topic,
            Integer depth
    ) {
        this.answer = answer;
        this.newNodeId = newNodeId;
        this.resolvedParentId = resolvedParentId;
        this.nodeTitle = nodeTitle;
        this.level1Topic = level1Topic;
        this.level2Topic = level2Topic;
        this.depth = depth;
    }
}
