package com.rabbit.domain.chat.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class NodeSummaryResponse {
    private Long nodeId;
    private String summaryTopic;
    private List<String> summaryPoints;
    private boolean processing;
}
