package com.rabbit.domain.chat.service;

import com.rabbit.domain.chat.dto.TopicExtractionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationTreePlannerService {

    private static final int TITLE_LIMIT = 24;
    private static final int TOPIC_LIMIT = 30;

    private final ConversationTreeAiService conversationTreeAiService;
    private final AiPreProcessorService aiPreProcessorService;

    // 🎯 [에러 해결] 파라미터 타입 완벽 일치! ChatService가 확정해서 넘겨준 String 2개를 그대로 받습니다.
    public TreePlan planNode(String level1Topic, String level2Topic, int currentDepth, String userMessage) {
        String normalizedMessage = normalize(userMessage);

        String nodeTitle;
        if (currentDepth <= 0) {
            nodeTitle = level1Topic;
        } else {
            // 🚨 기둥(Depth 1)이든 자식(Depth 2)이든 상관없이 무조건 유저 질문(userMessage) 기반으로 간판 제작!
            nodeTitle = resolveDeepNodeTitle(normalizedMessage, level1Topic, level2Topic, currentDepth);
        }

        nodeTitle = trimToLength(defaultIfBlank(nodeTitle, summarize(normalizedMessage, TITLE_LIMIT)), TITLE_LIMIT);
        String l1 = trimToLength(defaultIfBlank(level1Topic, "루트 주제"), TOPIC_LIMIT);
        String l2 = trimToLength(defaultIfBlank(level2Topic, "소주제"), TOPIC_LIMIT);

        return new TreePlan(nodeTitle, l1, l2);
    }

    public List<String> extractSeedSubtopics(String userMessage) {
        if (!isNotBlank(userMessage)) return List.of();
        TopicExtractionResponse extracted = aiPreProcessorService.parseUserInput(userMessage);
        List<String> minors = extracted.minorTopics();
        if (minors == null || minors.isEmpty()) return List.of();

        return minors.stream()
                .map(token -> normalize(token).replaceAll("^[-*\\d.\\s]+", "").replaceAll("[.!?]+$", "").trim())
                .filter(cleaned -> cleaned.length() >= 2).limit(10).toList();
    }

    public boolean isGenericSubtopic(String topic) {
        if (topic == null || topic.isBlank()) return true;
        String cleaned = topic.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return cleaned.contains("방법") || cleaned.contains("준비") || cleaned.contains("활용")
                || cleaned.contains("개념") || cleaned.contains("특징") || cleaned.contains("이해")
                || cleaned.contains("기초") || cleaned.contains("기본") || cleaned.contains("소개");
    }

    public String aiLabelForSubtopicMatch(String prompt) {
        try {
            return cleanModelOutput(conversationTreeAiService.selectBestSubtopic(prompt));
        } catch (Exception ignored) { return "NONE"; }
    }

    public String resolveDeepNodeTitle(String currentMessage, String level1Topic, String level2Topic, int depth) {
        // 🎯 앵무새 방지 및 AI 지식 개입 원천 차단 프롬프트
        String faithfulPrompt = "Task: Extract and format the core keyword strictly from the User Message into a short Korean noun phrase.\n"
                + "CRITICAL RULES:\n"
                + "1. DO NOT invent broad categories (like '알고리즘', '운영체제').\n"
                + "2. ONLY use the exact specific words present in the User Message.\n"
                + "3. Format it nicely as a noun phrase (e.g., '데드락 예시', '동기화 개념').\n"
                + "4. Output a clean 1-3 word noun phrase (Max 15 chars).\n"
                + "User Message: " + currentMessage;

        return aiLabel(faithfulPrompt, summarize(currentMessage, TITLE_LIMIT), TITLE_LIMIT);
    }

    public String aiLabel(String prompt, String fallback, int limit) {
        try {
            String cleaned = cleanModelOutput(conversationTreeAiService.generateNodeLabel(prompt));
            if (isNotBlank(cleaned)) return trimToLength(cleaned, limit);
        } catch (Exception ignored) {}
        return trimToLength(defaultIfBlank(fallback, "Untitled"), limit);
    }

    private String cleanModelOutput(String text) { if (!isNotBlank(text)) return ""; String firstLine = text.split("\\R", 2)[0]; return normalize(firstLine).replaceAll("^[-*\\d.\\s`\"']+", "").replaceAll("[`\"']+$", ""); }
    private String summarize(String text, int limit) { return trimToLength(normalize(text), limit); }
    private String trimToLength(String text, int maxLength) { if (!isNotBlank(text)) return ""; String normalized = text.trim(); if (normalized.length() <= maxLength) return normalized; if (maxLength <= 3) return normalized.substring(0, maxLength); return normalized.substring(0, maxLength - 3).trim() + "..."; }
    private String defaultIfBlank(String text, String fallback) { return isNotBlank(text) ? text.trim() : fallback; }
    private boolean isNotBlank(String text) { return text != null && !text.trim().isEmpty(); }
    private String normalize(String text) { if (text == null) return ""; return text.replaceAll("\\s+", " ").trim(); }

    public record TreePlan(String nodeTitle, String level1Topic, String level2Topic) {}
}
