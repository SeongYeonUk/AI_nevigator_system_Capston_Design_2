package com.rabbit.domain.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbit.domain.chat.dto.ConversationSummaryItemResponse;
import com.rabbit.domain.chat.entity.ChatMessage;
import com.rabbit.domain.chat.enums.SenderRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationInsightSummaryService {

    private static final int MAX_ITEMS = 6;
    private static final int MAX_DETAILS = 4;
    private static final int MAX_KEYWORD_LENGTH = 28;
    private static final int MAX_DETAIL_LENGTH = 52;

    private static final Pattern SUBTOPIC_PREFIX_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:\\[AUTO_SUBTOPIC\\]|소주제|sub\\s*topic)\\s*(?:[:\\-])?\\s*"
    );
    private static final Pattern ENDING_PATTERN = Pattern.compile(
            "(합니다|입니다|이다|해요|돼요|됩니다|였습니다|였다|알려주세요|알려줘|설명해줘)\\s*$"
    );
    private static final Pattern CLOSING_LINE_PATTERN = Pattern.compile(
            "(?i)(gpt\\s*api\\s*가\\s*동작하는\\s*방식|더\\s*알고\\s*싶은\\s*부분|질문이\\s*있다면\\s*더\\s*알려주세요)"
    );

    private final ConversationTreeAiService conversationTreeAiService;
    private final ObjectMapper objectMapper;

    public List<ConversationSummaryItemResponse> summarize(ChatMessage selectedNode) {
        ChatMessage aiNode = resolveAiNode(selectedNode);
        if (aiNode == null) {
            return List.of();
        }

        ChatMessage userNode = aiNode.getParent();
        if (userNode == null || userNode.getSender() != SenderRole.USER) {
            return fallbackSummary("", aiNode.getContent());
        }

        if (isAutoSeedNode(userNode, aiNode)) {
            return List.of();
        }

        String question = stripSystemPrefix(defaultString(userNode.getContent()));
        String answer = defaultString(aiNode.getContent());

        List<ConversationSummaryItemResponse> aiSummary = summarizeWithModel(question, answer);
        if (!aiSummary.isEmpty()) {
            return aiSummary;
        }
        return fallbackSummary(question, answer);
    }

    private List<ConversationSummaryItemResponse> summarizeWithModel(String question, String answer) {
        try {
            String prompt = """
                    [Task]
                    아래의 "선택 노드 질문 1개 + 해당 답변 1개"만 요약하십시오.
                    전체 대화 맥락을 확장하지 마십시오.

                    [Rules]
                    1) 핵심 단어/짧은 어구 중심으로 정리
                    2) 원문 문장을 길게 복붙 금지
                    3) '...합니다', '...이다' 같은 종결형 문장 금지
                    4) 마무리 안내 문구(추가 질문 유도 문장) 제외
                    5) summary_items는 3~6개, details는 항목당 1~3개

                    [선택 노드 질문]
                    %s

                    [선택 노드 답변]
                    %s

                    JSON으로만 응답:
                    {
                      "summary_items": [
                        { "keyword": "핵심 키워드", "details": ["짧은 어구", "짧은 어구"] }
                      ]
                    }
                    """.formatted(question, answer);

            String raw = conversationTreeAiService.summarizeSelectedNodeQa(prompt);
            return parseSummaryItems(raw);
        } catch (Exception e) {
            log.debug("Selected-node summary generation failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ConversationSummaryItemResponse> parseSummaryItems(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        String json = extractJsonObject(raw);
        if (json.isBlank()) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode items = root.path("summary_items");
            if (!items.isArray()) {
                return List.of();
            }

            List<ConversationSummaryItemResponse> result = new ArrayList<>();
            for (JsonNode item : items) {
                if (result.size() >= MAX_ITEMS) {
                    break;
                }

                String keyword = cleanPhrase(item.path("keyword").asText(""), MAX_KEYWORD_LENGTH);
                List<String> details = new ArrayList<>();
                JsonNode detailsNode = item.path("details");
                if (detailsNode.isArray()) {
                    for (JsonNode detailNode : detailsNode) {
                        if (details.size() >= MAX_DETAILS) {
                            break;
                        }
                        String detail = cleanPhrase(detailNode.asText(""), MAX_DETAIL_LENGTH);
                        if (!detail.isBlank()) {
                            details.add(detail);
                        }
                    }
                }

                if (keyword.isBlank() && details.isEmpty()) {
                    continue;
                }
                if (keyword.isBlank()) {
                    keyword = details.get(0);
                }
                if (details.isEmpty()) {
                    details.add("핵심 포인트");
                }

                result.add(ConversationSummaryItemResponse.builder()
                        .keyword(keyword)
                        .details(details)
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.debug("Selected-node summary parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return raw.substring(start, end + 1).trim();
    }

    private List<ConversationSummaryItemResponse> fallbackSummary(String question, String answer) {
        String keyword = cleanPhrase(question, MAX_KEYWORD_LENGTH);
        if (keyword.isBlank()) {
            keyword = cleanPhrase(firstMeaningfulLine(answer), MAX_KEYWORD_LENGTH);
        }
        if (keyword.isBlank()) {
            keyword = "핵심 요약";
        }

        List<String> details = new ArrayList<>();
        for (String rawLine : defaultString(answer).replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (details.size() >= MAX_DETAILS) {
                break;
            }
            String detail = cleanPhrase(rawLine, MAX_DETAIL_LENGTH);
            if (!detail.isBlank()) {
                details.add(detail);
            }
        }

        if (details.isEmpty()) {
            String fallback = cleanPhrase(question, MAX_DETAIL_LENGTH);
            details.add(fallback.isBlank() ? "핵심 내용 정리" : fallback);
        }

        return List.of(ConversationSummaryItemResponse.builder()
                .keyword(keyword)
                .details(deduplicate(details))
                .build());
    }

    private List<String> deduplicate(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String normalized = value.trim();
            if (!normalized.isBlank()) {
                unique.add(normalized);
            }
        }
        return new ArrayList<>(unique);
    }

    private String firstMeaningfulLine(String text) {
        for (String rawLine : defaultString(text).replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = cleanPhrase(rawLine, MAX_KEYWORD_LENGTH);
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
    }

    private String cleanPhrase(String text, int maxLength) {
        String cleaned = defaultString(text);
        cleaned = stripSystemPrefix(cleaned);
        cleaned = cleaned.replace("**", "").replace("__", "");
        cleaned = cleaned.replaceAll("^\\s*[-*\\d.)\\s]+", "");
        cleaned = cleaned.replaceAll("\\s+", " ");
        cleaned = cleaned.replaceAll("[.!?]+$", "").trim();
        cleaned = ENDING_PATTERN.matcher(cleaned).replaceFirst("").trim();

        if (cleaned.endsWith(":") || cleaned.endsWith("：")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        if (isClosingLine(cleaned)) {
            return "";
        }
        if (cleaned.length() > maxLength) {
            cleaned = cleaned.substring(0, maxLength - 1).trim() + "...";
        }
        return cleaned;
    }

    private boolean isClosingLine(String line) {
        return CLOSING_LINE_PATTERN.matcher(defaultString(line)).find();
    }

    private String stripSystemPrefix(String text) {
        if (text == null) {
            return "";
        }
        return SUBTOPIC_PREFIX_PATTERN.matcher(text).replaceFirst("").trim();
    }

    private boolean isAutoSeedNode(ChatMessage userNode, ChatMessage aiNode) {
        String userText = defaultString(userNode.getContent());
        String aiText = defaultString(aiNode.getContent());
        return userText.startsWith("[AUTO_SUBTOPIC]") || (userText.startsWith("소주제:") && aiText.contains("초기 소주제"));
    }

    private ChatMessage resolveAiNode(ChatMessage node) {
        if (node == null) {
            return null;
        }
        if (node.getSender() == SenderRole.AI) {
            return node;
        }
        ChatMessage parent = node.getParent();
        if (parent != null && parent.getSender() == SenderRole.AI) {
            return parent;
        }
        return null;
    }

    private String defaultString(String text) {
        return text == null ? "" : text.trim();
    }
}
