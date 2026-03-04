package com.rabbit.domain.chat.service;

import com.rabbit.domain.chat.entity.ChatMessage;
import com.rabbit.domain.chat.enums.SenderRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ConversationTreePlannerService {

    private static final int TITLE_LIMIT = 24;
    private static final int TOPIC_LIMIT = 30;

    private static final Pattern ROOT_PATTERN = Pattern.compile(
            "(?i)(?:\\broot\\s*topic\\b|\\blevel\\s*1\\b|\\bmain\\s*topic\\b|대주제|루트\\s*노드|루트)\\s*[:：]\\s*([^\\n,;|]+)");
    private static final Pattern SUB_PATTERN = Pattern.compile(
            "(?i)(?:\\bsub\\s*topic\\b|\\blevel\\s*2\\b|\\bsecond\\s*topic\\b|소주제|서브\\s*토픽|2레벨)\\s*[:：]\\s*([^\\n,;|]+)");

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
        level1Topic = trimToLength(defaultIfBlank(level1Topic, "Root Topic"), TOPIC_LIMIT);
        level2Topic = trimToLength(defaultIfBlank(level2Topic, "Subtopic"), TOPIC_LIMIT);

        return new TreePlan(nodeTitle, level1Topic, level2Topic);
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

            String fallback = summarize(currentMessage, TOPIC_LIMIT);
            return aiLabel(
                    "Task: create level-1 root topic.\nUser message: " + currentMessage,
                    fallback,
                    TOPIC_LIMIT
            );
        }

        Optional<ChatMessage> rootUser = history.stream()
                .filter(this::isUserMessage)
                .filter(message -> message.getDepth() == 0)
                .findFirst();

        if (rootUser.isPresent()) {
            ChatMessage root = rootUser.get();
            if (isNotBlank(root.getLevel1Topic())) {
                return root.getLevel1Topic();
            }
            String explicit = extract(ROOT_PATTERN, root.getContent());
            if (isNotBlank(explicit)) {
                return explicit;
            }
            return summarize(root.getContent(), TOPIC_LIMIT);
        }

        String explicit = extract(ROOT_PATTERN, currentMessage);
        if (isNotBlank(explicit)) {
            return explicit;
        }
        return summarize(currentMessage, TOPIC_LIMIT);
    }

    private String resolveLevel2Topic(
            List<ChatMessage> history,
            ChatMessage parentAiNode,
            int depth,
            String currentMessage,
            String level1Topic
    ) {
        if (depth == 1) {
            String explicit = extract(SUB_PATTERN, currentMessage);
            if (isNotBlank(explicit)) {
                return explicit;
            }

            String fallback = summarize(currentMessage, TOPIC_LIMIT);
            return aiLabel(
                    "Task: create level-2 subtopic under level-1 topic.\n"
                            + "Level-1 topic: " + level1Topic + "\n"
                            + "User message: " + currentMessage,
                    fallback,
                    TOPIC_LIMIT
            );
        }

        if (depth > 1) {
            ChatMessage branchDepthOneUser = findBranchDepthOneUser(parentAiNode);
            if (branchDepthOneUser != null) {
                if (isNotBlank(branchDepthOneUser.getLevel2Topic())) {
                    return branchDepthOneUser.getLevel2Topic();
                }

                String explicit = extract(SUB_PATTERN, branchDepthOneUser.getContent());
                if (isNotBlank(explicit)) {
                    return explicit;
                }
                return summarize(branchDepthOneUser.getContent(), TOPIC_LIMIT);
            }
        }

        Optional<ChatMessage> firstDepthOne = history.stream()
                .filter(this::isUserMessage)
                .filter(message -> message.getDepth() == 1)
                .findFirst();

        if (firstDepthOne.isPresent()) {
            ChatMessage levelTwo = firstDepthOne.get();
            if (isNotBlank(levelTwo.getLevel2Topic())) {
                return levelTwo.getLevel2Topic();
            }

            String explicit = extract(SUB_PATTERN, levelTwo.getContent());
            if (isNotBlank(explicit)) {
                return explicit;
            }
            return summarize(levelTwo.getContent(), TOPIC_LIMIT);
        }

        if (depth == 0) {
            return "Subtopic";
        }

        return summarize(currentMessage, TOPIC_LIMIT);
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
        String fallback = summarize(currentMessage, TITLE_LIMIT);
        return aiLabel(
                "Task: create conversation-tree node title for depth >= 2.\n"
                        + "Level-1 topic: " + level1Topic + "\n"
                        + "Level-2 topic: " + level2Topic + "\n"
                        + "Depth: " + depth + "\n"
                        + "User message: " + currentMessage,
                fallback,
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
            // Fall back to deterministic summary if model call fails.
        }
        return trimToLength(defaultIfBlank(fallback, "Untitled"), limit);
    }

    private String cleanModelOutput(String text) {
        if (!isNotBlank(text)) {
            return "";
        }
        String firstLine = text.split("\\R", 2)[0];
        String cleaned = normalize(firstLine)
                .replaceAll("^[-*\\d.\\s`\"']+", "")
                .replaceAll("[`\"']+$", "");
        return normalize(cleaned);
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

    private String summarize(String text, int limit) {
        if (!isNotBlank(text)) {
            return "Untitled";
        }

        String cleaned = normalize(text)
                .replaceAll("(?i)(?:\\broot\\s*topic\\b|\\blevel\\s*1\\b|\\bmain\\s*topic\\b|대주제|루트\\s*노드|루트)\\s*[:：]", "")
                .replaceAll("(?i)(?:\\bsub\\s*topic\\b|\\blevel\\s*2\\b|\\bsecond\\s*topic\\b|소주제|서브\\s*토픽|2레벨)\\s*[:：]", "")
                .trim();

        int sentenceEnd = indexOfSentenceEnd(cleaned);
        if (sentenceEnd > 0) {
            cleaned = cleaned.substring(0, sentenceEnd).trim();
        }

        cleaned = cleaned.replaceAll("\\s+", " ");
        if (!isNotBlank(cleaned)) {
            cleaned = "Untitled";
        }
        return trimToLength(cleaned, limit);
    }

    private int indexOfSentenceEnd(String text) {
        int best = -1;
        for (char ch : new char[]{'.', '?', '!', '\n'}) {
            int idx = text.indexOf(ch);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    private String trimToLength(String text, int limit) {
        String normalized = normalize(text);
        if (normalized.length() <= limit) {
            return normalized;
        }
        if (limit <= 3) {
            return normalized.substring(0, limit);
        }
        return normalized.substring(0, limit - 3).trim() + "...";
    }

    private String defaultIfBlank(String value, String fallback) {
        return isNotBlank(value) ? value : fallback;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isUserMessage(ChatMessage message) {
        return message != null && message.getSender() == SenderRole.USER;
    }

    public record TreePlan(String nodeTitle, String level1Topic, String level2Topic) {
    }
}
