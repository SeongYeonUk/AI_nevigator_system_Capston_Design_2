package com.rabbit.domain.chat.service;

import com.rabbit.domain.chat.Repository.ChatMessageRepository;
import com.rabbit.domain.chat.Repository.ChatRoomRepository;
import com.rabbit.domain.chat.dto.ChatHistoryResponse;
import com.rabbit.domain.chat.dto.ChatResponse;
import com.rabbit.domain.chat.dto.ChatRoomResponse;
import com.rabbit.domain.chat.dto.ConversationTreeNodeResponse;
import com.rabbit.domain.chat.dto.ConversationTreeResponse;
import com.rabbit.domain.chat.dto.NodeInsightResponse;
import com.rabbit.domain.chat.entity.ChatMessage;
import com.rabbit.domain.chat.entity.ChatRoom;
import com.rabbit.domain.chat.enums.SenderRole;
import com.rabbit.domain.user.repository.UserRepository;
import com.rabbit.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Pattern SUBTOPIC_PATTERN = Pattern.compile(
            "(?i)(?:소주제(?:는)?|sub\\s*topic(?:s)?|level\\s*2)\\s*[:：]?\\s*([^\\n]+)");
    private static final Pattern TAIL_PHRASE_PATTERN = Pattern.compile(
            "(?:야|이야|입니다|이에요|예요|임|라고|라고요)$");
    private static final Pattern SUBTOPIC_PREFIX_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:\\[AUTO_SUBTOPIC\\]|소주제|sub\\s*topic)\\s*[:：]?\\s*");

    private static final Set<String> STOP_WORDS = Set.of(
            "대해", "알려줘", "설명", "설명해", "해주세요", "해줘",
            "그리고", "그냥", "관련", "관해", "알려", "내용",
            "이", "가", "은", "는", "을", "를", "에", "의"
    );

    private final RabbitGuardService rabbitGuardService;
    private final ConversationTreeAiService conversationTreeAiService;
    private final ConversationTreePlannerService conversationTreePlannerService;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    @Transactional
    public Long createRoom(String authorization, String title) {
        validateAuthorization(authorization);

        ChatRoom room = ChatRoom.builder()
                .title(title)
                .build();
        return chatRoomRepository.save(room).getId();
    }

    @Transactional
    public void deleteRoom(Long roomId) {
        chatRoomRepository.deleteById(roomId);
    }

    @Transactional
    public void updateRoomTitle(String authorization, Long roomId, String title) {
        validateAuthorization(authorization);

        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Title must not be empty.");
        }

        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Chat room not found."));
        room.changeTitle(trimmed);
    }

    @Transactional(readOnly = true)
    public List<ChatRoomResponse> getRoomList(String authorization) {
        validateAuthorization(authorization);

        return chatRoomRepository.findAll()
                .stream()
                .map(room -> new ChatRoomResponse(room.getId(), room.getTitle(), room.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @Transactional
    public ChatResponse ask(String authorization, Long roomId, Long parentId, String userMessage) {
        validateAuthorization(authorization);

        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Chat room not found."));

        List<ChatMessage> roomHistory = chatMessageRepository.findByChatRoomIdOrderByCreatedAtAsc(roomId);

        ChatMessage requestedParent = (parentId != null)
                ? chatMessageRepository.findById(parentId).orElse(null)
                : null;
        ChatMessage parentNode = resolveParentNodeForIntent(requestedParent, roomHistory, userMessage);

        int currentDepth = (parentNode == null) ? 0 : parentNode.getDepth() + 1;

        ConversationTreePlannerService.TreePlan treePlan = conversationTreePlannerService.planNode(
                roomHistory,
                parentNode,
                currentDepth,
                userMessage
        );

        ChatMessage userSaved = chatMessageRepository.save(ChatMessage.builder()
                .chatRoom(room)
                .parent(parentNode)
                .sender(SenderRole.USER)
                .content(userMessage)
                .nodeTitle(treePlan.nodeTitle())
                .level1Topic(treePlan.level1Topic())
                .level2Topic(treePlan.level2Topic())
                .depth(currentDepth)
                .build());

        String aiAnswer = rabbitGuardService.chat(roomId, userMessage);

        ChatMessage aiSaved = chatMessageRepository.save(ChatMessage.builder()
                .chatRoom(room)
                .parent(userSaved)
                .sender(SenderRole.AI)
                .content(aiAnswer)
                .depth(currentDepth)
                .build());

        if (currentDepth == 0) {
            createInitialLevelTwoSeedNodes(room, aiSaved, treePlan.level1Topic(), userMessage);
        }

        return ChatResponse.builder()
                .answer(aiAnswer)
                .newNodeId(aiSaved.getId())
                .nodeTitle(treePlan.nodeTitle())
                .level1Topic(treePlan.level1Topic())
                .level2Topic(treePlan.level2Topic())
                .depth(currentDepth)
                .build();
    }

    private ChatMessage resolveParentNodeForIntent(ChatMessage requestedParent, List<ChatMessage> history, String userMessage) {
        if (history == null || history.isEmpty()) {
            return requestedParent;
        }

        List<SubtopicAnchor> anchors = findSubtopicAnchors(history);
        if (anchors.isEmpty()) {
            return requestedParent;
        }

        String normalizedMessage = normalizeForMatch(userMessage);
        if (normalizedMessage.isBlank()) {
            return requestedParent;
        }

        SubtopicAnchor directMatch = anchors.stream()
                .filter(anchor -> containsTopic(normalizedMessage, anchor.topic()))
                .max(Comparator.comparingInt(anchor -> normalizeForMatch(anchor.topic()).length()))
                .orElse(null);
        if (directMatch != null) {
            // If the currently selected node is already under the matched subtopic branch, keep it.
            if (requestedParent != null && isDescendantOf(requestedParent, directMatch.aiNode())) {
                // Keep descending only when the new message clearly continues the current lower branch context.
                if (isContinuationOfCurrentBranch(requestedParent, userMessage)) {
                    return requestedParent;
                }
                return directMatch.aiNode();
            }
            // Otherwise, move the branch root to the matched level-2 node.
            return directMatch.aiNode();
        }

        ChatMessage keywordRouted = routeByKeywordOverlap(anchors, history, userMessage);
        if (keywordRouted != null) {
            if (requestedParent != null && isDescendantOf(requestedParent, keywordRouted)) {
                if (isContinuationOfCurrentBranch(requestedParent, userMessage)) {
                    return requestedParent;
                }
            }
            return keywordRouted;
        }

        ChatMessage aiRouted = routeByAiSelection(anchors, userMessage);
        if (aiRouted != null) {
            if (requestedParent != null && isDescendantOf(requestedParent, aiRouted)) {
                if (isContinuationOfCurrentBranch(requestedParent, userMessage)) {
                    return requestedParent;
                }
            }
            return aiRouted;
        }

        // No explicit/implicit reroute found: honor user-selected branch.
        if (requestedParent != null && requestedParent.getDepth() >= 1) {
            return requestedParent;
        }

        return requestedParent;
    }

    private ChatMessage routeByKeywordOverlap(List<SubtopicAnchor> anchors, List<ChatMessage> history, String userMessage) {
        Set<String> messageKeywords = extractKeywords(userMessage);
        if (messageKeywords.isEmpty()) {
            return null;
        }

        int bestScore = 0;
        SubtopicAnchor bestAnchor = null;
        boolean tied = false;

        for (SubtopicAnchor anchor : anchors) {
            Set<String> branchKeywords = buildBranchKeywords(anchor, history);
            if (branchKeywords.isEmpty()) {
                continue;
            }

            int score = 0;
            for (String keyword : messageKeywords) {
                if (branchKeywords.contains(keyword)) {
                    score++;
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestAnchor = anchor;
                tied = false;
            } else if (score > 0 && score == bestScore) {
                tied = true;
            }
        }

        if (bestAnchor == null || bestScore == 0 || tied) {
            return null;
        }
        return bestAnchor.aiNode();
    }

    private Set<String> buildBranchKeywords(SubtopicAnchor anchor, List<ChatMessage> history) {
        Set<String> keywords = new HashSet<>();
        keywords.addAll(extractKeywords(anchor.topic()));

        if (history == null || history.isEmpty()) {
            return keywords;
        }

        for (ChatMessage message : history) {
            if (message.getSender() != SenderRole.USER || message.getDepth() < 2) {
                continue;
            }
            if (!isDescendantOf(message, anchor.aiNode())) {
                continue;
            }

            if (message.getNodeTitle() != null) {
                keywords.addAll(extractKeywords(stripSystemPrefix(message.getNodeTitle())));
            }
            if (message.getContent() != null) {
                keywords.addAll(extractKeywords(stripSystemPrefix(message.getContent())));
            }
        }

        return keywords;
    }

    private Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        String normalized = text.toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return Set.of();
        }

        Set<String> keywords = new HashSet<>();
        for (String token : normalized.split(" ")) {
            String cleaned = token.trim();
            if (cleaned.length() < 2) {
                continue;
            }
            if (STOP_WORDS.contains(cleaned)) {
                continue;
            }
            keywords.add(cleaned);
        }
        return keywords;
    }

    private boolean isDescendantOf(ChatMessage node, ChatMessage ancestor) {
        if (node == null || ancestor == null || ancestor.getId() == null) {
            return false;
        }

        ChatMessage cursor = node;
        while (cursor != null) {
            if (cursor.getId() != null && cursor.getId().equals(ancestor.getId())) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }

    private boolean isContinuationOfCurrentBranch(ChatMessage requestedParent, String userMessage) {
        if (requestedParent == null || userMessage == null || userMessage.isBlank()) {
            return false;
        }

        String normalizedMessage = normalizeForMatch(userMessage);
        if (normalizedMessage.isBlank()) {
            return false;
        }

        ChatMessage userNode = requestedParent.getSender() == SenderRole.AI && requestedParent.getParent() != null
                ? requestedParent.getParent()
                : requestedParent;

        List<String> hints = new ArrayList<>();
        if (userNode != null) {
            if (userNode.getNodeTitle() != null && !userNode.getNodeTitle().isBlank()) {
                hints.add(userNode.getNodeTitle());
            }
            if (userNode.getLevel2Topic() != null && !userNode.getLevel2Topic().isBlank()) {
                hints.add(userNode.getLevel2Topic());
            }
            if (userNode.getContent() != null && !userNode.getContent().isBlank()) {
                hints.add(userNode.getContent());
            }
        }

        for (String hint : hints) {
            String normalizedHint = normalizeForMatch(stripSystemPrefix(hint));
            if (normalizedHint.length() < 2) {
                continue;
            }
            if (normalizedMessage.contains(normalizedHint)) {
                return true;
            }
        }
        return false;
    }

    private ChatMessage routeByAiSelection(List<SubtopicAnchor> anchors, String userMessage) {
        if (anchors.size() == 1) {
            return anchors.get(0).aiNode();
        }

        try {
            StringBuilder options = new StringBuilder();
            for (int i = 0; i < anchors.size(); i++) {
                options.append(i + 1).append(") ").append(anchors.get(i).topic()).append('\n');
            }

            String prompt = "User message:\n"
                    + userMessage
                    + "\n\nCandidate level-2 topics:\n"
                    + options
                    + "\nReturn exactly one candidate text, or NONE if no match.";
            String selected = cleanModelOutput(conversationTreeAiService.selectBestSubtopic(prompt));
            if (selected.isBlank() || "NONE".equalsIgnoreCase(selected)) {
                return null;
            }

            String normalizedSelected = normalizeForMatch(selected);
            if (normalizedSelected.isBlank()) {
                return null;
            }

            for (SubtopicAnchor anchor : anchors) {
                String normalizedTopic = normalizeForMatch(anchor.topic());
                if (normalizedTopic.equals(normalizedSelected)
                        || normalizedTopic.contains(normalizedSelected)
                        || normalizedSelected.contains(normalizedTopic)) {
                    return anchor.aiNode();
                }
            }
        } catch (Exception ignored) {
            // If AI routing fails, keep requested parent.
        }

        return null;
    }

    private List<SubtopicAnchor> findSubtopicAnchors(List<ChatMessage> history) {
        Map<Long, ChatMessage> aiByUserId = history.stream()
                .filter(message -> message.getSender() == SenderRole.AI)
                .filter(message -> message.getParent() != null)
                .collect(Collectors.toMap(
                        message -> message.getParent().getId(),
                        message -> message,
                        (first, second) -> first
                ));

        LinkedHashMap<String, SubtopicAnchor> deduplicated = new LinkedHashMap<>();
        for (ChatMessage message : history) {
            if (message.getSender() != SenderRole.USER || message.getDepth() != 1) {
                continue;
            }

            ChatMessage aiNode = aiByUserId.get(message.getId());
            if (aiNode == null) {
                continue;
            }

            String topic = extractSubtopicLabel(message);
            String key = normalizeForMatch(topic);
            if (key.isBlank() || deduplicated.containsKey(key)) {
                continue;
            }

            deduplicated.put(key, new SubtopicAnchor(topic, aiNode));
        }

        return new ArrayList<>(deduplicated.values());
    }

    private String extractSubtopicLabel(ChatMessage message) {
        if (message.getLevel2Topic() != null && !message.getLevel2Topic().isBlank()) {
            return message.getLevel2Topic().trim();
        }
        if (message.getNodeTitle() != null && !message.getNodeTitle().isBlank()) {
            return message.getNodeTitle().trim();
        }

        String content = message.getContent() == null ? "" : message.getContent().trim();
        String stripped = SUBTOPIC_PREFIX_PATTERN.matcher(content).replaceFirst("").trim();
        if (!stripped.isBlank()) {
            return trimToLength(stripped, 40);
        }
        return compactNodeTitle(message);
    }

    private boolean containsTopic(String normalizedMessage, String topic) {
        String normalizedTopic = normalizeForMatch(topic);
        return !normalizedTopic.isBlank() && normalizedMessage.contains(normalizedTopic);
    }

    private String normalizeForMatch(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private String cleanModelOutput(String text) {
        if (text == null) {
            return "";
        }
        String firstLine = text.split("\\R", 2)[0];
        return firstLine
                .replaceAll("^[-*\\d.\\s`\"']+", "")
                .replaceAll("[`\"']+$", "")
                .trim();
    }

    private String stripSystemPrefix(String text) {
        if (text == null) {
            return "";
        }
        return SUBTOPIC_PREFIX_PATTERN.matcher(text).replaceFirst("").trim();
    }

    private void createInitialLevelTwoSeedNodes(ChatRoom room, ChatMessage rootAiNode, String level1Topic, String userMessage) {
        List<String> subtopics = extractInitialSubtopics(userMessage);
        if (subtopics.isEmpty()) {
            return;
        }

        for (String subtopic : subtopics) {
            ChatMessage seedUser = chatMessageRepository.save(ChatMessage.builder()
                    .chatRoom(room)
                    .parent(rootAiNode)
                    .sender(SenderRole.USER)
                    .content("[AUTO_SUBTOPIC] " + subtopic)
                    .nodeTitle(subtopic)
                    .level1Topic(level1Topic)
                    .level2Topic(subtopic)
                    .depth(1)
                    .build());

            chatMessageRepository.save(ChatMessage.builder()
                    .chatRoom(room)
                    .parent(seedUser)
                    .sender(SenderRole.AI)
                    .content("[AUTO_SUBTOPIC_ACK] " + subtopic)
                    .depth(1)
                    .build());
        }
    }

    private List<String> extractInitialSubtopics(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }

        Matcher matcher = SUBTOPIC_PATTERN.matcher(message);
        if (!matcher.find()) {
            return List.of();
        }

        String raw = matcher.group(1);
        String normalized = raw.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return List.of();
        }

        String[] split = normalized.split(",|/|\\||;|·|•|\\band\\b|및|그리고");
        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        for (String token : split) {
            String cleaned = token == null ? "" : token.trim();
            if (cleaned.isEmpty()) {
                continue;
            }

            cleaned = cleaned.replaceAll("^[-*\\d.\\s]+", "");
            cleaned = cleaned.replaceAll("[.!?]+$", "");
            cleaned = TAIL_PHRASE_PATTERN.matcher(cleaned).replaceAll("").trim();

            if (cleaned.length() >= 2) {
                deduplicated.add(cleaned);
            }
        }

        return deduplicated.stream().limit(10).toList();
    }

    @Transactional(readOnly = true)
    public List<ChatHistoryResponse> getHistory(String authorization, Long roomId) {
        validateAuthorization(authorization);

        return chatMessageRepository.findByChatRoomIdOrderByCreatedAtAsc(roomId)
                .stream()
                .map(ChatHistoryResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ConversationTreeResponse getConversationTree(String authorization, Long roomId) {
        validateAuthorization(authorization);

        List<ChatMessage> history = chatMessageRepository.findByChatRoomIdOrderByCreatedAtAsc(roomId);
        Map<Long, ChatMessage> aiByUserId = history.stream()
                .filter(message -> message.getSender() == SenderRole.AI)
                .filter(message -> message.getParent() != null)
                .collect(Collectors.toMap(
                        message -> message.getParent().getId(),
                        message -> message,
                        (first, second) -> first
                ));

        List<ConversationTreeNodeResponse> nodes = new ArrayList<>();
        LinkedHashSet<String> level2Topics = new LinkedHashSet<>();
        String level1Topic = null;

        for (ChatMessage message : history) {
            if (message.getSender() != SenderRole.USER) {
                continue;
            }

            ChatMessage ai = aiByUserId.get(message.getId());
            if (ai == null) {
                continue;
            }

            Long parentNodeId = null;
            if (message.getParent() != null) {
                ChatMessage parent = message.getParent();
                if (parent.getSender() == SenderRole.AI) {
                    parentNodeId = parent.getId();
                } else {
                    ChatMessage parentAi = aiByUserId.get(parent.getId());
                    parentNodeId = parentAi != null ? parentAi.getId() : null;
                }
            }

            String nodeTitle = compactNodeTitle(message);
            if (message.getDepth() == 0 && level1Topic == null) {
                level1Topic = message.getLevel1Topic();
                if (level1Topic == null || level1Topic.isBlank()) {
                    level1Topic = nodeTitle;
                }
            }

            if (message.getDepth() == 1 && message.getLevel2Topic() != null && !message.getLevel2Topic().isBlank()) {
                level2Topics.add(message.getLevel2Topic());
            } else if (message.getDepth() == 1) {
                level2Topics.add(nodeTitle);
            }

            nodes.add(ConversationTreeNodeResponse.builder()
                    .id(ai.getId())
                    .parentId(parentNodeId)
                    .title(nodeTitle)
                    .userQuestion(message.getContent())
                    .aiAnswer(ai.getContent())
                    .depth(message.getDepth())
                    .createdAt(ai.getCreatedAt())
                    .build());
        }

        if ((level1Topic == null || level1Topic.isBlank()) && !nodes.isEmpty()) {
            level1Topic = nodes.get(0).getTitle();
        }
        if (level1Topic == null || level1Topic.isBlank()) {
            level1Topic = "Root Topic";
        }

        return ConversationTreeResponse.builder()
                .roomId(roomId)
                .level1Topic(level1Topic)
                .level2Topics(new ArrayList<>(level2Topics))
                .totalNodes(nodes.size())
                .nodes(nodes)
                .build();
    }

    @Transactional(readOnly = true)
    public NodeInsightResponse getNodeInsight(Long nodeId) {
        ChatMessage node = chatMessageRepository.findById(nodeId)
                .orElseThrow(() -> new RuntimeException("Node not found."));

        String parentPath = "none";
        if (node.getDepth() > 0) {
            StringBuilder sb = new StringBuilder("n");
            for (int i = 0; i < node.getDepth(); i++) {
                int userIndex = (i * 2) + 1;
                sb.append("_").append(userIndex);
            }
            parentPath = sb.toString();
        }

        double ratio = Math.min(100, ((double) node.getDepth() / 7) * 100);
        String titleSource = (node.getNodeTitle() != null && !node.getNodeTitle().isBlank())
                ? node.getNodeTitle()
                : node.getContent();

        return NodeInsightResponse.builder()
                .title(titleSource.substring(0, Math.min(titleSource.length(), 8)))
                .depth(node.getDepth())
                .parentPath(parentPath)
                .progressRatio(ratio)
                .alertMessage(node.getDepth() >= 5 ? "Warning: depth is high." : "Stable.")
                .build();
    }

    private String compactNodeTitle(ChatMessage message) {
        if (message.getNodeTitle() != null && !message.getNodeTitle().isBlank()) {
            return message.getNodeTitle().trim();
        }

        String content = message.getContent() == null ? "" : message.getContent().trim();
        if (content.isBlank()) {
            return "Untitled";
        }

        String clean = content.replaceAll("\\s+", " ");
        return trimToLength(clean, 24);
    }

    private String trimToLength(String text, int maxLength) {
        if (text == null) {
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

    private void validateAuthorization(String rawToken) {
        String token = stripBearer(rawToken);
        if (!jwtTokenProvider.validateToken(token)) {
            throw new IllegalArgumentException("Invalid token.");
        }

        String loginId = jwtTokenProvider.getLoginId(token);
        if (!userRepository.existsByLoginId(loginId)) {
            throw new IllegalArgumentException("User not found.");
        }
    }

    private String stripBearer(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Missing access token.");
        }
        if (rawToken.startsWith("Bearer ")) {
            return rawToken.substring(7).trim();
        }
        return rawToken.trim();
    }

    private record SubtopicAnchor(String topic, ChatMessage aiNode) {
    }
}
