package com.rabbit.domain.chat.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NodeInsightResponse {
    private String title;
    private int depth;
    private String parentPath;
    private double progressRatio;
    private String alertMessage;
}