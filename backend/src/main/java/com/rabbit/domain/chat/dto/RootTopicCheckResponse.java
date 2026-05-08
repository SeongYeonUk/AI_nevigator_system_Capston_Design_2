package com.rabbit.domain.chat.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class RootTopicCheckResponse {
    private boolean unrelated;
    private String rootTopic;
    private double similarity;
    private String message;

    @Builder
    public RootTopicCheckResponse(boolean unrelated, String rootTopic, double similarity, String message) {
        this.unrelated = unrelated;
        this.rootTopic = rootTopic;
        this.similarity = similarity;
        this.message = message;
    }
}
