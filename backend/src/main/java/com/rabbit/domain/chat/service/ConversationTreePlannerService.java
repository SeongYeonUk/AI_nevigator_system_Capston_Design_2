package com.rabbit.domain.chat.service;

import com.rabbit.domain.chat.entity.ChatMessage;
import com.rabbit.domain.chat.enums.SenderRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ConversationTreePlannerService {

    private static final int TITLE_LIMIT = 24;
    private static final int TOPIC_LIMIT = 30;

    private static final Pattern ROOT_PATTERN = Pattern.compile(
            "(?i)(?:\\broot\\s*topic\\b|\\blevel\\s*1\\b|\\bmain\\s*topic\\b|대주제|루트\\s*주제|루트)\\s*(?:[:\\-]|은|는|이야|야)?\\s*([^\\n,;|]+)"
    );
    private static final Pattern SUB_PATTERN = Pattern.compile(
            "(?i)(?:\\bsub\\s*topics?\\b|\\blevel\\s*2\\b|\\bsecond\\s*topic\\b|소주제|하위\\s*주제)\\s*(?:[:\\-]|은|는|이야|야)?\\s*([^\\n]+)"
    );

    private final ConversationTreeAiService conversationTreeAiService;

    public TreePlan planNode(List<ChatMessage> roomHistory, ChatMessage parentAiNode, int currentDepth, String userMessage) {
        List<ChatMessage> orderedHistory = ordered(roomHistory);
        String normalizedMessage = normalize(userMessage);

        String level1Topic = resolveLevel1Topic(orderedHistory, currentDepth, normalizedMessage);
        String level2Topic = resolveLevel2Topic(orderedHistory, parentAiNode, currentDepth, normalizedMessage, level1Topic);

        String nodeTitle;
        if (currentDepth <= 0) {
            nodeTitle = level1Topic;
        } else if (currentDepth == 1) {
            nodeTitle = level2Topic;
        } else {
            nodeTitle = resolveDeepNodeTitle(normalizedMessage, level1Topic, level2Topic, currentDepth);
        }

        nodeTitle = trimToLength(defaultIfBlank(nodeTitle, summarize(normalizedMessage, TITLE_LIMIT)), TITLE_LIMIT);
        level1Topic = trimToLength(defaultIfBlank(level1Topic, "루트 주제"), TOPIC_LIMIT);
        level2Topic = trimToLength(defaultIfBlank(level2Topic, "소주제"), TOPIC_LIMIT);

        return new TreePlan(nodeTitle, level1Topic, level2Topic);
    }

    public List<String> extractSeedSubtopics(String userMessage) {
        if (!isNotBlank(userMessage)) {
            return List.of();

        }

        Matcher matcher = SUB_PATTERN.matcher(userMessage);
        if (!matcher.find()) {
            return List.of();
        }

        String raw = matcher.group(1);
        if (!isNotBlank(raw)) {
            return List.of();
        }

        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        for (String token : raw.split(",|/|\\||;|\\band\\b|그리고|및")) {
            String cleaned = normalize(token)
                    .replaceAll("^[-*\\d.\\s]+", "")
                    .replaceAll("[.!?]+$", "")
                    .replaceAll("(?:이야|야|입니다|이에요|이고|고)$", "")
                    .trim();
            if (cleaned.length() >= 2) {
                deduplicated.add(cleaned);
            }
        }
        return deduplicated.stream().limit(10).toList();
    }

    private List<ChatMessage> ordered(List<ChatMessage> roomHistory) {
        if (roomHistory == null || roomHistory.isEmpty()) {
            return List.of();
        }
        return roomHistory.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt)
                        .thenComparing(ChatMessage::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    private String resolveLevel1Topic(List<ChatMessage> history, int depth, String currentMessage) {
        if (depth == 0) {
            String explicit = extract(ROOT_PATTERN, currentMessage);
            if (isNotBlank(explicit)) {
                return explicit;
            }
            return aiLabel(
                    "Task: create level-1 root topic.\nUser message: " + currentMessage,
                    summarize(currentMessage, TOPIC_LIMIT),
                    TOPIC_LIMIT
            );
        }

        return history.stream()
                .filter(this::isUserMessage)
                .filter(message -> message.getDepth() == 0)
                .map(ChatMessage::getLevel1Topic)
                .filter(this::isNotBlank)
                .findFirst()
                .orElseGet(() -> summarize(currentMessage, TOPIC_LIMIT));
    }

    private String resolveLevel2Topic(List<ChatMessage> history, ChatMessage parentAiNode, int depth, String currentMessage, String level1Topic) {
        if (depth == 1) {
            String explicit = extract(SUB_PATTERN, currentMessage);
            if (isNotBlank(explicit)) {
                return explicit;
            }
            return aiLabel(
                    "Task: create level-2 subtopic under level-1 topic.\nLevel-1 topic: " + level1Topic + "\nUser message: " + currentMessage,
                    summarize(currentMessage, TOPIC_LIMIT),
                    TOPIC_LIMIT
            );
        }

        ChatMessage branchDepthOneUser = findBranchDepthOneUser(parentAiNode);
        if (branchDepthOneUser != null) {
            if (isNotBlank(branchDepthOneUser.getLevel2Topic())) {
                return branchDepthOneUser.getLevel2Topic();
            }
            return summarize(branchDepthOneUser.getContent(), TOPIC_LIMIT);
        }

        return history.stream()
                .filter(this::isUserMessage)
                .filter(message -> message.getDepth() == 1)
                .map(message -> defaultIfBlank(message.getLevel2Topic(), message.getNodeTitle()))
                .filter(this::isNotBlank)
                .findFirst()
                .orElse(depth == 0 ? "소주제" : summarize(currentMessage, TOPIC_LIMIT));
    }

    private ChatMessage findBranchDepthOneUser(ChatMessage parentAiNode) {
        ChatMessage cursor = parentAiNode;
        while (cursor != null) {
            ChatMessage userParent = cursor.getParent();
            if (userParent == null) {
                return null;
            }
            if (userParent.getDepth() == 1) {
                return userParent;
            }
            cursor = userParent.getParent();
        }
        return null;
    }

    private String resolveDeepNodeTitle(String currentMessage, String level1Topic, String level2Topic, int depth) {
        return aiLabel(
                "Task: create conversation-tree node title for depth >= 2.\n"
                        + "Level-1 topic: " + level1Topic + "\n"
                        + "Level-2 topic: " + level2Topic + "\n"
                        + "Depth: " + depth + "\n"
                        + "User message: " + currentMessage,
                summarize(currentMessage, TITLE_LIMIT),
                TITLE_LIMIT
        );
    }

    private String aiLabel(String prompt, String fallback, int limit) {
        try {
            String raw = conversationTreeAiService.generateNodeLabel(prompt);
            String cleaned = cleanModelOutput(raw);
            if (isNotBlank(cleaned)) {
                return trimToLength(cleaned, limit);
            }
        } catch (Exception ignored) {
        }
        return trimToLength(defaultIfBlank(fallback, "Untitled"), limit);
    }

    private String extract(Pattern pattern, String text) {
        if (!isNotBlank(text)) {
            return "";
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        return normalize(matcher.group(1));
    }

    private boolean isUserMessage(ChatMessage message) {
        return message != null && message.getSender() == SenderRole.USER;
    }

    private String cleanModelOutput(String text) {
        if (!isNotBlank(text)) {
            return "";
        }
        String firstLine = text.split("\\R", 2)[0];
        return normalize(firstLine)
                .replaceAll("^[-*\\d.\\s`\"']+", "")
                .replaceAll("[`\"']+$", "");
    }

    private String summarize(String text, int limit) {
        return trimToLength(normalize(text), limit);
    }

    private String trimToLength(String text, int maxLength) {
        if (!isNotBlank(text)) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        if (maxLength <= 3) {
            return normalized.substring(0, maxLength);
        }
        return normalized.substring(0, maxLength - 3).trim() + "...";
    }

    private String defaultIfBlank(String text, String fallback) {
        return isNotBlank(text) ? text.trim() : fallback;
    }

    private boolean isNotBlank(String text) {
        return text != null && !text.trim().isEmpty();
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    public record TreePlan(String nodeTitle, String level1Topic, String level2Topic) {
    }
}
