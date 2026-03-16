
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {

        private static final Pattern SUBTOPIC_PATTERN = Pattern.compile(
            "(?i)(?:소주제|하위\\s*주제|sub\\s*topics?|level\\s*2)\\s*(?:[:\\-]|은|는|이야|야)?\\s*([^\\n]+)");
    private static final Pattern TAIL_PHRASE_PATTERN = Pattern.compile(
            "(?:이야|야|입니다|이에요|이고|고)$");
    private static final Pattern SUBTOPIC_PREFIX_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:\\[AUTO_SUBTOPIC\\]|소주제|sub\\s*topic)\\s*(?:[:\\-])?\\s*");
    private static final Pattern HINT_SPLIT_PATTERN = Pattern.compile(
            ",|/|\\||;|\\band\\b|그리고|및",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FOLLOW_UP_PATTERN = Pattern.compile(
            "(?i)(더\\s*자세히|자세히|구체적으로|예시|왜|어떻게|차이|비교|심화|추가\\s*설명|more|detail|example|why|compare)"
    );

    private static final Set<String> STOP_WORDS = Set.of(
            "설명", "알려줘", "알려", "정의", "개념", "방법", "예시", "자세히", "관련", "정보",
            "what", "how", "why", "tell", "about", "please", "more", "detail"
    );

    private final RabbitGuardService rabbitGuardService;
    private final ConversationTreeAiService conversationTreeAiService;
    private final ConversationTreePlannerService conversationTreePlannerService;
    private final ContextSimilarityService contextSimilarityService;
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
    public void deleteRoom(String authorization, Long roomId) {
        validateAuthorization(authorization);

        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Chat room not found."));

        chatMessageRepository.clearParentByRoomId(roomId);
        chatMessageRepository.deleteAllByChatRoomId(roomId);
        chatRoomRepository.delete(room);
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

        SubtopicDecision decision = classifyTargetAnchor(anchors, history, requestedParent, userMessage);
        if (decision != null && decision.anchor() != null) {
            ChatMessage chosen = chooseParentWithinAnchor(decision.anchor(), requestedParent, history, userMessage);
            log.info("Chat routing selected subtopic='{}' reason='{}'", decision.anchor().topic(), decision.reason());
            return chosen;
        }

        return requestedParent;
    }
    private SubtopicDecision classifyTargetAnchor(
            List<SubtopicAnchor> anchors,
            List<ChatMessage> history,
            ChatMessage requestedParent,
            String userMessage
    ) {
        String normalizedMessage = normalizeForMatch(userMessage);
        if (normalizedMessage.isBlank()) {
            return null;
        }

        SubtopicAnchor directMatch = anchors.stream()
                .filter(anchor -> containsTopic(normalizedMessage, anchor.topic()))
                .max(Comparator.comparingInt(anchor -> normalizeForMatch(anchor.topic()).length()))
                .orElse(null);
        if (directMatch != null) {
            return new SubtopicDecision(directMatch, "direct-topic-match");
        }

        SubtopicAnchor aiSelectedAnchor = selectAnchorByAi(anchors, history, userMessage);

        double bestScore = Double.NEGATIVE_INFINITY;
        double secondScore = Double.NEGATIVE_INFINITY;
        SubtopicAnchor bestAnchor = null;
        String bestReason = null;

        for (SubtopicAnchor anchor : anchors) {
            boolean strongMatch = contextSimilarityService.stronglyMatchesTopic(userMessage, branchDescriptor(anchor));
            double hintScore = contextSimilarityService.hintOverlapScore(userMessage, branchDescriptor(anchor));
            double keywordScore = keywordOverlapScore(anchor, history, userMessage);
            double similarityScore = contextSimilarityService.score(
                    userMessage,
                    branchDescriptor(anchor),
                    buildBranchSimilaritySamples(anchor, history)
            );
            boolean aiSelected = aiSelectedAnchor != null && anchor.aiNode().getId().equals(aiSelectedAnchor.aiNode().getId());

            double totalScore = (strongMatch ? 8.0 : 0.0)
                    + (hintScore * 2.8)
                    + (keywordScore * 1.6)
                    + (similarityScore * 6.5)
                    + (aiSelected ? 4.5 : 0.0);

            if (requestedParent != null && isDescendantOf(requestedParent, anchor.aiNode())) {
                if (isContinuationOfCurrentBranch(requestedParent, userMessage)) {
                    totalScore += 1.2;
                } else {
                    totalScore -= 1.5;
                }
            }

            log.info(
                    "Routing candidate topic='{}' strong={} hints={} keywords={} similarity={} aiSelected={} total={}",
                    anchor.topic(), strongMatch, round(hintScore), round(keywordScore), round(similarityScore), aiSelected, round(totalScore)
            );

            if (totalScore > bestScore) {
                secondScore = bestScore;
                bestScore = totalScore;
                bestAnchor = anchor;
                bestReason = buildReason(strongMatch, hintScore, keywordScore, similarityScore, aiSelected);
            } else if (totalScore > secondScore) {
                secondScore = totalScore;
            }
        }

        if (bestAnchor == null) {
            return null;
        }

        double margin = bestScore - secondScore;
        if (bestScore >= 2.2 && margin >= 1.0) {
            return new SubtopicDecision(bestAnchor, bestReason + ", margin=" + round(margin));
        }

        return null;
    }

    private ChatMessage chooseParentWithinAnchor(
            SubtopicAnchor anchor,
            ChatMessage requestedParent,
            List<ChatMessage> history,
            String userMessage
    ) {
        if (requestedParent == null) {
            return anchor.aiNode();
        }
        if (!isDescendantOf(requestedParent, anchor.aiNode())) {
            return anchor.aiNode();
        }
        if (isContinuationOfCurrentBranch(requestedParent, userMessage)) {
            return requestedParent;
        }
        return anchor.aiNode();
    }

    private double keywordOverlapScore(SubtopicAnchor anchor, List<ChatMessage> history, String userMessage) {
        Set<String> messageKeywords = extractKeywords(userMessage);
        if (messageKeywords.isEmpty()) {
            return 0.0;
        }

        Set<String> branchKeywords = buildBranchKeywords(anchor, history);
        if (branchKeywords.isEmpty()) {
            return 0.0;
        }

        int matched = 0;
        for (String keyword : messageKeywords) {
            if (branchKeywords.contains(keyword)) {
                matched++;
            }
        }
        return matched / (double) Math.max(messageKeywords.size(), 1);
    }

    private Set<String> buildBranchKeywords(SubtopicAnchor anchor, List<ChatMessage> history) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        keywords.addAll(extractKeywords(anchor.topic()));
        keywords.addAll(extractKeywords(anchor.hints()));

        for (ChatMessage message : history) {
            if (message.getSender() != SenderRole.USER || message.getDepth() < 2) {
                continue;
            }
            if (!isDescendantOf(message, anchor.aiNode())) {
                continue;
            }
            keywords.addAll(extractKeywords(message.getNodeTitle()));
            keywords.addAll(extractKeywords(message.getContent()));
        }
        return keywords;
    }

    private List<String> buildBranchSimilaritySamples(SubtopicAnchor anchor, List<ChatMessage> history) {
        List<String> samples = new ArrayList<>();
        samples.add(anchor.topic());
        if (anchor.hints() != null && !anchor.hints().isBlank()) {
            samples.add(anchor.hints());
        }

        for (ChatMessage message : history) {
            if (!isDescendantOf(message, anchor.aiNode())) {
                continue;
            }
            if (message.getSender() == SenderRole.USER) {
                addIfNotBlank(samples, message.getNodeTitle());
                addIfNotBlank(samples, stripSystemPrefix(message.getContent()));
            } else {
                addIfNotBlank(samples, stripSystemPrefix(message.getContent()));
            }
        }
        return samples.stream().limit(12).toList();
    }

    private Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return Set.of();
        }

        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            String cleaned = token.trim();
            if (cleaned.length() < 2 || STOP_WORDS.contains(cleaned)) {
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

        if (looksLikeFollowUpQuestion(userMessage)) {
            return true;
        }

        ChatMessage userNode = requestedParent.getSender() == SenderRole.AI && requestedParent.getParent() != null
                ? requestedParent.getParent()
                : requestedParent;

        List<String> contextSamples = new ArrayList<>();
        if (userNode != null) {
            addIfNotBlank(contextSamples, userNode.getNodeTitle());
            addIfNotBlank(contextSamples, userNode.getLevel2Topic());
            addIfNotBlank(contextSamples, userNode.getContent());
        }
        if (requestedParent.getSender() == SenderRole.AI) {
            addIfNotBlank(contextSamples, requestedParent.getContent());
        }

        String normalizedMessage = normalizeForMatch(userMessage);
        for (String sample : contextSamples) {
            String normalizedSample = normalizeForMatch(stripSystemPrefix(sample));
            if (normalizedSample.length() >= 2 && normalizedMessage.contains(normalizedSample)) {
                return true;
            }
        }

        double relationshipScore = contextSimilarityService.relationshipScore(userMessage, contextSamples);
        return relationshipScore >= 0.34;
    }

    private boolean looksLikeFollowUpQuestion(String userMessage) {
        return userMessage != null && FOLLOW_UP_PATTERN.matcher(userMessage).find();
    }
    private SubtopicAnchor selectAnchorByAi(List<SubtopicAnchor> anchors, List<ChatMessage> history, String userMessage) {
        if (anchors.isEmpty()) {
            return null;
        }
        if (anchors.size() == 1) {
            return anchors.get(0);
        }

        try {
            StringBuilder options = new StringBuilder();
            for (int i = 0; i < anchors.size(); i++) {
                SubtopicAnchor anchor = anchors.get(i);
                options.append(i + 1)
                        .append(") ")
                        .append(anchor.topic())
                        .append(" | hints: ")
                        .append(defaultString(anchor.hints()))
                        .append(" | samples: ")
                        .append(String.join(" / ", buildBranchSimilaritySamples(anchor, history).stream().limit(4).toList()))
                        .append('\n');
            }

            String prompt = "User question:\n" + userMessage
                    + "\n\nCandidate subtopics:\n" + options
                    + "\nReturn exactly one candidate text or NONE.";
            String selected = cleanModelOutput(conversationTreeAiService.selectBestSubtopic(prompt));
            if (selected.isBlank() || "NONE".equalsIgnoreCase(selected)) {
                return null;
            }

            String normalizedSelected = normalizeForMatch(selected);
            for (SubtopicAnchor anchor : anchors) {
                String normalizedTopic = normalizeForMatch(anchor.topic());
                if (normalizedTopic.equals(normalizedSelected)
                        || normalizedTopic.contains(normalizedSelected)
                        || normalizedSelected.contains(normalizedTopic)) {
                    return anchor;
                }
            }
        } catch (Exception e) {
            log.debug("AI subtopic selection failed: {}", e.getMessage());
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
            String hints = message.getTopicHints();
            if (hints == null || hints.isBlank()) {
                hints = generateSubtopicHints(message.getLevel1Topic(), topic, collectSiblingTopics(history, message));
                message.updateTopicHints(hints);
            }

            String key = normalizeForMatch(topic);
            if (key.isBlank() || deduplicated.containsKey(key)) {
                continue;
            }
            deduplicated.put(key, new SubtopicAnchor(topic, hints, aiNode));
        }

        return new ArrayList<>(deduplicated.values());
    }

    private List<String> collectSiblingTopics(List<ChatMessage> history, ChatMessage current) {
        List<String> siblings = new ArrayList<>();
        for (ChatMessage message : history) {
            if (message.getSender() != SenderRole.USER || message.getDepth() != 1 || message.getId().equals(current.getId())) {
                continue;
            }
            siblings.add(extractSubtopicLabel(message));
        }
        return siblings;
    }

    private String extractSubtopicLabel(ChatMessage message) {
        if (message.getLevel2Topic() != null && !message.getLevel2Topic().isBlank()) {
            return message.getLevel2Topic().trim();
        }
        if (message.getNodeTitle() != null && !message.getNodeTitle().isBlank()) {
            return message.getNodeTitle().trim();
        }

        String stripped = stripSystemPrefix(defaultString(message.getContent()));
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
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
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
        List<String> subtopics = conversationTreePlannerService.extractSeedSubtopics(userMessage);
        if (subtopics.isEmpty()) {
            subtopics = extractInitialSubtopics(userMessage);
        }
        if (subtopics.isEmpty()) {
            return;
        }

        for (String subtopic : subtopics) {
            String hints = generateSubtopicHints(level1Topic, subtopic, subtopics);
            ChatMessage seedUser = chatMessageRepository.save(ChatMessage.builder()
                    .chatRoom(room)
                    .parent(rootAiNode)
                    .sender(SenderRole.USER)
                    .content("[AUTO_SUBTOPIC] " + subtopic)
                    .nodeTitle(subtopic)
                    .level1Topic(level1Topic)
                    .level2Topic(subtopic)
                    .topicHints(hints)
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
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        for (String token : HINT_SPLIT_PATTERN.split(raw)) {
            String cleaned = token == null ? "" : token.trim();
            cleaned = cleaned.replaceAll("^[-*\\d.\\s]+", "");
            cleaned = cleaned.replaceAll("[.!?]+$", "");
            cleaned = TAIL_PHRASE_PATTERN.matcher(cleaned).replaceAll("").trim();
            if (cleaned.length() >= 2) {
                deduplicated.add(cleaned);
            }
        }
        return deduplicated.stream().limit(10).toList();
    }

    private String generateSubtopicHints(String level1Topic, String subtopic, List<String> siblingTopics) {
        try {
            String prompt = "Root topic: " + defaultString(level1Topic)
                    + "\nSubtopic: " + defaultString(subtopic)
                    + "\nSibling subtopics: " + String.join(", ", siblingTopics)
                    + "\nReturn only comma-separated concepts.";
            String raw = conversationTreeAiService.generateSubtopicHints(prompt);
            String normalized = normalizeHintList(raw);
            if (!normalized.isBlank()) {
                return normalized;
            }
        } catch (Exception e) {
            log.debug("Topic hint generation failed for subtopic '{}': {}", subtopic, e.getMessage());
        }
        return subtopic;
    }

    private String normalizeHintList(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        for (String token : HINT_SPLIT_PATTERN.split(raw)) {
            String cleaned = cleanModelOutput(token);
            cleaned = cleaned.replaceAll("^[-*\\d.\\s]+", "").trim();
            if (cleaned.length() >= 2) {
                deduplicated.add(cleaned);
            }
        }
        return String.join(", ", deduplicated.stream().limit(10).toList());
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
                level1Topic = defaultString(message.getLevel1Topic());
                if (level1Topic.isBlank()) {
                    level1Topic = nodeTitle;
                }
            }

            if (message.getDepth() == 1) {
                level2Topics.add(defaultString(message.getLevel2Topic()).isBlank() ? nodeTitle : message.getLevel2Topic());
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
                .title(titleSource.substring(0, Math.min(titleSource.length(), 24)))
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

        String content = stripSystemPrefix(defaultString(message.getContent()));
        if (content.isBlank()) {
            return "Untitled";
        }
        return trimToLength(content.replaceAll("\\s+", " "), 24);
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

    private void addIfNotBlank(List<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private String branchDescriptor(SubtopicAnchor anchor) {
        if (anchor.hints() == null || anchor.hints().isBlank()) {
            return anchor.topic();
        }
        return anchor.topic() + ", " + anchor.hints();
    }

    private String buildReason(boolean strongMatch, double hintScore, double keywordScore, double similarityScore, boolean aiSelected) {
        return "strong=" + strongMatch
                + ", hints=" + round(hintScore)
                + ", keywords=" + round(keywordScore)
                + ", similarity=" + round(similarityScore)
                + ", aiSelected=" + aiSelected;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String defaultString(String text) {
        return text == null ? "" : text.trim();
    }

    private record SubtopicAnchor(String topic, String hints, ChatMessage aiNode) {
    }

    private record SubtopicDecision(SubtopicAnchor anchor, String reason) {
    }
}
