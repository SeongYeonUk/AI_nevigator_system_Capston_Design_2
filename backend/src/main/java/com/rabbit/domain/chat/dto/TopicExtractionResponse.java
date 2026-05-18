package com.rabbit.domain.chat.dto;

import java.util.List;

import java.util.List;

public record TopicExtractionResponse(
        String majorTopic,
        List<String> minorTopics
) {}
