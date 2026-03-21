
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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

    private static final double ROUTING_CONFIDENCE_SCORE_THRESHOLD = 2.2;
    private static final double ROUTING_CONFIDENCE_MARGIN_THRESHOLD = 1.0;
    private static final double REEVALUATION_SCORE_THRESHOLD = 1.8;
    private static final double REEVALUATION_MARGIN_THRESHOLD = 0.35;
    private static final double CROSS_ANCHOR_REEVALUATION_SCORE_THRESHOLD = 3.4;
    private static final double CROSS_ANCHOR_REEVALUATION_MARGIN_THRESHOLD = 1.3;
    private static final long LOW_CONFIDENCE_REEVALUATION_DELAY_MS = 900L;
    private static final Pattern SERIES_COMBINED_TOKEN_PATTERN = Pattern.compile("([\\p{L}]*)(\\d+)([\\p{L}]*)");
    private static final Pattern SIBLING_INTENT_PATTERN = Pattern.compile(
            "(?i)(another|different|next|other|sibling|also|then|vs|versus|\\uB2E4\\uC74C|\\uB2E4\\uB978|\\uB610|\\uC774\\uBC88\\uC5D0\\uB294)"
    );
    private static final Pattern CHILD_EXPANSION_PATTERN = Pattern.compile(
            "(?i)(condition|conditions|type|types|kind|kinds|cause|causes|solution|solutions|principle|principles|structure|step|steps|criteria|rule|rules|\\uC870\\uAC74|\\uC885\\uB958|\\uC6D0\\uC778|\\uD574\\uACB0|\\uC6D0\\uB9AC|\\uAD6C\\uC870|\\uACFC\\uC815|\\uAE30\\uC900|\\uADDC\\uCE59)"
    );
    private static final double DEFAULT_RELATIONSHIP_THRESHOLD = 0.34;
    private static final double SHORT_QUERY_RELATIONSHIP_THRESHOLD = 0.24;
    private static final double SHORT_QUERY_TOPIC_CARRYOVER_THRESHOLD = 0.34;
    private static final double CHILD_EXPANSION_TOPIC_CARRYOVER_THRESHOLD = 0.40;
    private static final double INDEXED_CHILD_TOPIC_CARRYOVER_THRESHOLD = 0.50;
    private static final double ANCHOR_PARENT_SCORE_GAP_THRESHOLD = 0.08;
    private static final double PARENT_REBALANCE_SCORE_GAP_THRESHOLD = 0.10;

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
    private final TransactionTemplate transactionTemplate;

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

        ChatMessage requestedParent = (parentId != null)
                ? chatMessageRepository.findById(parentId).orElse(null)
                : null;

        int currentDepth = (requestedParent == null) ? 0 : requestedParent.getDepth() + 1;
        String fallbackNodeTitle = trimToLength(defaultString(userMessage).replaceAll("\\s+", " "), 24);
        String fallbackLevel1Topic = (requestedParent != null && requestedParent.getParent() != null)
                ? defaultString(requestedParent.getParent().getLevel1Topic())
                : fallbackNodeTitle;
        if (fallbackLevel1Topic.isBlank()) {
            fallbackLevel1Topic = fallbackNodeTitle.isBlank() ? "Root Topic" : fallbackNodeTitle;
        }
        String fallbackLevel2Topic = (requestedParent != null && requestedParent.getParent() != null)
                ? defaultString(requestedParent.getParent().getLevel2Topic())
                : "Subtopic";
        if (fallbackLevel2Topic.isBlank()) {
            fallbackLevel2Topic = "Subtopic";
        }

        ChatMessage userSaved = chatMessageRepository.save(ChatMessage.builder()
                .chatRoom(room)
                .parent(requestedParent)
                .sender(SenderRole.USER)
                .content(userMessage)
                .nodeTitle(fallbackNodeTitle)
                .level1Topic(fallbackLevel1Topic)
                .level2Topic(fallbackLevel2Topic)
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

        triggerTreePostProcessingAsync(roomId, parentId, userMessage, userSaved.getId(), aiSaved.getId());

        return ChatResponse.builder()
                .answer(aiAnswer)
                .newNodeId(aiSaved.getId())
                .resolvedParentId(requestedParent != null ? requestedParent.getId() : null)
                .nodeTitle(fallbackNodeTitle)
                .level1Topic(fallbackLevel1Topic)
                .level2Topic(fallbackLevel2Topic)
                .depth(currentDepth)
                .build();
    }

    private void triggerTreePostProcessingAsync(
            Long roomId,
            Long requestedParentId,
            String userMessage,
            Long userMessageId,
            Long aiMessageId
    ) {
        Runnable postProcessTask = () -> CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    try {
                        applyTreePostProcessing(roomId, requestedParentId, userMessage, userMessageId, aiMessageId);
                    } catch (Exception e) {
                        log.warn("Tree post-processing failed for room {}: {}", roomId, e.getMessage());
                    }
                })
        );

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    postProcessTask.run();
                }
            });
            return;
        }

        postProcessTask.run();
    }

    private void applyTreePostProcessing(
            Long roomId,
            Long requestedParentId,
            String userMessage,
            Long userMessageId,
            Long aiMessageId
    ) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Chat room not found."));
        ChatMessage userSaved = chatMessageRepository.findById(userMessageId)
                .orElseThrow(() -> new IllegalArgumentException("User node not found."));
        ChatMessage aiSaved = chatMessageRepository.findById(aiMessageId)
                .orElseThrow(() -> new IllegalArgumentException("AI node not found."));

        List<ChatMessage> roomHistory = chatMessageRepository.findByChatRoomIdOrderByCreatedAtAsc(roomId);
        List<ChatMessage> historyBeforeCurrent = roomHistory.stream()
                .filter(message -> !Objects.equals(message.getId(), userMessageId))
                .filter(message -> !Objects.equals(message.getId(), aiMessageId))
                .toList();

        ChatMessage requestedParent = (requestedParentId != null)
                ? chatMessageRepository.findById(requestedParentId).orElse(null)
                : null;
        ParentResolution parentResolution = resolveParentNodeForIntent(requestedParent, historyBeforeCurrent, userMessage);
        ChatMessage parentNode = parentResolution.parentNode();
        int currentDepth = (parentNode == null) ? 0 : parentNode.getDepth() + 1;

        ConversationTreePlannerService.TreePlan treePlan = conversationTreePlannerService.planNode(
                historyBeforeCurrent,
                parentNode,
                currentDepth,
                userMessage
        );

        userSaved.updateTreePlacement(parentNode, currentDepth);
        userSaved.updateTreeMetadata(treePlan.nodeTitle(), treePlan.level1Topic(), treePlan.level2Topic());
        ensureNodeTopicHints(userSaved, historyBeforeCurrent);
        aiSaved.updateDepth(currentDepth);

        if (currentDepth == 0) {
            createInitialLevelTwoSeedNodes(room, aiSaved, treePlan.level1Topic(), userMessage);
        }

        if (parentResolution.needsReevaluation()) {
            triggerLowConfidenceReevaluationAsync(roomId, requestedParentId, userMessage, userMessageId, aiMessageId);
        }
    }

    private ParentResolution resolveParentNodeForIntent(ChatMessage requestedParent, List<ChatMessage> history, String userMessage) {
        if (history == null || history.isEmpty()) {
            return new ParentResolution(requestedParent, false);
        }

        List<SubtopicAnchor> anchors = findSubtopicAnchors(history);
        if (anchors.isEmpty()) {
            return new ParentResolution(requestedParent, false);
        }

        SubtopicRanking ranking = rankSubtopicAnchors(anchors, history, userMessage);
        if (ranking == null || ranking.bestAnchor() == null) {
            return new ParentResolution(requestedParent, false);
        }

        if (isConfidentRouting(ranking)) {
            ChatMessage chosen = chooseParentWithinAnchor(ranking.bestAnchor(), requestedParent, history, userMessage);
            log.info("Chat routing selected subtopic='{}' reason='{}'", ranking.bestAnchor().topic(),
                    ranking.reason() + ", margin=" + round(ranking.margin()));
            return new ParentResolution(chosen, false);
        }

        if (isReevaluationCandidate(ranking)) {
            log.info(
                    "Chat routing low-confidence. preserving requested parent and scheduling reevaluation. topic='{}' score={} margin={} reason='{}'",
                    ranking.bestAnchor().topic(), round(ranking.bestScore()), round(ranking.margin()), ranking.reason()
            );
            return new ParentResolution(requestedParent, true);
        }

        return new ParentResolution(requestedParent, false);
    }

    private SubtopicRanking rankSubtopicAnchors(
            List<SubtopicAnchor> anchors,
            List<ChatMessage> history,
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
            return new SubtopicRanking(directMatch, "direct-topic-match", 100.0, 100.0);
        }

        QuestionIntent intent = detectQuestionIntent(userMessage);
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
                    buildAnchorProfileSamples(anchor, history)
            );
            double centroidScore = centroidAnchorScore(anchor, history, userMessage);
            double questionTypeTermScore = questionTypeAndTermScore(intent, anchor, history, userMessage);
            boolean aiSelected = aiSelectedAnchor != null && anchor.aiNode().getId().equals(aiSelectedAnchor.aiNode().getId());

            double totalScore = (strongMatch ? 8.0 : 0.0)
                    + (hintScore * 2.6)
                    + (keywordScore * 1.5)
                    + (similarityScore * 5.8)
                    + (centroidScore * 4.0)
                    + (questionTypeTermScore * 2.2)
                    + (aiSelected ? 3.8 : 0.0);

            log.info(
                    "Routing candidate topic='{}' strong={} hints={} keywords={} similarity={} centroid={} qTypeTerm={} aiSelected={} total={}",
                    anchor.topic(), strongMatch, round(hintScore), round(keywordScore), round(similarityScore),
                    round(centroidScore), round(questionTypeTermScore), aiSelected, round(totalScore)
            );

            if (totalScore > bestScore) {
                secondScore = bestScore;
                bestScore = totalScore;
                bestAnchor = anchor;
                bestReason = buildReason(
                        strongMatch,
                        hintScore,
                        keywordScore,
                        similarityScore,
                        centroidScore,
                        questionTypeTermScore,
                        aiSelected,
                        intent
                );
            } else if (totalScore > secondScore) {
                secondScore = totalScore;
            }
        }

        if (bestAnchor == null) {
            return null;
        }

        double margin = Double.isFinite(secondScore) ? bestScore - secondScore : bestScore;
        return new SubtopicRanking(bestAnchor, bestReason, bestScore, margin);
    }

    private boolean isConfidentRouting(SubtopicRanking ranking) {
        return ranking != null
                && ranking.bestAnchor() != null
                && ranking.bestScore() >= ROUTING_CONFIDENCE_SCORE_THRESHOLD
                && ranking.margin() >= ROUTING_CONFIDENCE_MARGIN_THRESHOLD;
    }

    private boolean isReevaluationCandidate(SubtopicRanking ranking) {
        return ranking != null
                && ranking.bestAnchor() != null
                && ranking.bestScore() >= REEVALUATION_SCORE_THRESHOLD
                && ranking.margin() >= REEVALUATION_MARGIN_THRESHOLD;
    }

    private void triggerLowConfidenceReevaluationAsync(
            Long roomId,
            Long requestedParentId,
            String userMessage,
            Long userMessageId,
            Long aiMessageId
    ) {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(LOW_CONFIDENCE_REEVALUATION_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            transactionTemplate.executeWithoutResult(status -> {
                try {
                    applyLowConfidenceReevaluation(roomId, requestedParentId, userMessage, userMessageId, aiMessageId);
                } catch (Exception e) {
                    log.debug("Low-confidence reevaluation failed for room {}: {}", roomId, e.getMessage());
                }
            });
        });
    }

    private void applyLowConfidenceReevaluation(
            Long roomId,
            Long requestedParentId,
            String userMessage,
            Long userMessageId,
            Long aiMessageId
    ) {
        ChatMessage userSaved = chatMessageRepository.findById(userMessageId).orElse(null);
        ChatMessage aiSaved = chatMessageRepository.findById(aiMessageId).orElse(null);
        if (userSaved == null || aiSaved == null) {
            return;
        }

        List<ChatMessage> roomHistory = chatMessageRepository.findByChatRoomIdOrderByCreatedAtAsc(roomId);
        if (hasDescendantMessages(roomHistory, aiSaved, userMessageId, aiMessageId)) {
            return;
        }

        List<ChatMessage> historyBeforeCurrent = roomHistory.stream()
                .filter(message -> !Objects.equals(message.getId(), userMessageId))
                .filter(message -> !Objects.equals(message.getId(), aiMessageId))
                .toList();

        List<SubtopicAnchor> anchors = findSubtopicAnchors(historyBeforeCurrent);
        if (anchors.isEmpty()) {
            return;
        }

        SubtopicRanking reranked = rankSubtopicAnchors(anchors, historyBeforeCurrent, userMessage);
        if (!isReevaluationCandidate(reranked)) {
            return;
        }

        ChatMessage currentParent = userSaved.getParent();
        SubtopicAnchor currentAnchor = findAnchorForParent(currentParent, anchors);
        SubtopicAnchor reevaluationAnchor = selectReevaluationAnchor(reranked, currentAnchor);
        if (reevaluationAnchor == null) {
            return;
        }

        ChatMessage reevaluatedParent = chooseParentWithinAnchor(reevaluationAnchor, currentParent, historyBeforeCurrent, userMessage);
        int reevaluatedDepth = (reevaluatedParent == null) ? 0 : reevaluatedParent.getDepth() + 1;
        Long currentParentId = userSaved.getParent() != null ? userSaved.getParent().getId() : null;
        Long reevaluatedParentId = reevaluatedParent != null ? reevaluatedParent.getId() : null;

        if (Objects.equals(currentParentId, reevaluatedParentId) && userSaved.getDepth() == reevaluatedDepth) {
            return;
        }

        ConversationTreePlannerService.TreePlan treePlan = conversationTreePlannerService.planNode(
                historyBeforeCurrent,
                reevaluatedParent,
                reevaluatedDepth,
                userMessage
        );
        userSaved.updateTreePlacement(reevaluatedParent, reevaluatedDepth);
        userSaved.updateTreeMetadata(treePlan.nodeTitle(), treePlan.level1Topic(), treePlan.level2Topic());
        ensureNodeTopicHints(userSaved, historyBeforeCurrent);
        aiSaved.updateDepth(reevaluatedDepth);

        log.info(
                "Low-confidence routing reevaluated: topic='{}' score={} margin={} newParentId={} depth={}",
                reevaluationAnchor.topic(),
                round(reranked.bestScore()),
                round(reranked.margin()),
                reevaluatedParentId,
                reevaluatedDepth
        );
    }

    private boolean hasDescendantMessages(List<ChatMessage> roomHistory, ChatMessage aiNode, Long userMessageId, Long aiMessageId) {
        if (roomHistory == null || roomHistory.isEmpty() || aiNode == null) {
            return false;
        }

        for (ChatMessage message : roomHistory) {
            if (Objects.equals(message.getId(), userMessageId) || Objects.equals(message.getId(), aiMessageId)) {
                continue;
            }
            if (isDescendantOf(message, aiNode)) {
                return true;
            }
        }
        return false;
    }

    private SubtopicAnchor findAnchorForParent(ChatMessage parentNode, List<SubtopicAnchor> anchors) {
        if (parentNode == null || anchors == null || anchors.isEmpty()) {
            return null;
        }

        for (SubtopicAnchor anchor : anchors) {
            if (isDescendantOf(parentNode, anchor.aiNode())) {
                return anchor;
            }
        }
        return null;
    }

    private SubtopicAnchor selectReevaluationAnchor(SubtopicRanking reranked, SubtopicAnchor currentAnchor) {
        if (reranked == null || reranked.bestAnchor() == null) {
            return null;
        }
        if (currentAnchor == null) {
            return reranked.bestAnchor();
        }
        if (Objects.equals(currentAnchor.aiNode().getId(), reranked.bestAnchor().aiNode().getId())) {
            return currentAnchor;
        }

        boolean allowCrossAnchorMove = reranked.bestScore() >= CROSS_ANCHOR_REEVALUATION_SCORE_THRESHOLD
                && reranked.margin() >= CROSS_ANCHOR_REEVALUATION_MARGIN_THRESHOLD;
        return allowCrossAnchorMove ? reranked.bestAnchor() : currentAnchor;
    }

    private QuestionIntent detectQuestionIntent(String userMessage) {
        String normalized = normalizeForMatch(userMessage);
        if (normalized.isBlank()) {
            return QuestionIntent.OTHER;
        }

        if (containsAnyCue(normalized, QuestionIntent.COMPARISON.cues())) {
            return QuestionIntent.COMPARISON;
        }
        if (containsAnyCue(normalized, QuestionIntent.PROCEDURE.cues())) {
            return QuestionIntent.PROCEDURE;
        }
        if (containsAnyCue(normalized, QuestionIntent.EXAMPLE.cues())) {
            return QuestionIntent.EXAMPLE;
        }
        if (containsAnyCue(normalized, QuestionIntent.CAUSAL.cues())) {
            return QuestionIntent.CAUSAL;
        }
        if (containsAnyCue(normalized, QuestionIntent.DEFINITION.cues())) {
            return QuestionIntent.DEFINITION;
        }
        return QuestionIntent.OTHER;
    }

    private double questionTypeAndTermScore(
            QuestionIntent intent,
            SubtopicAnchor anchor,
            List<ChatMessage> history,
            String userMessage
    ) {
        double intentScore = questionIntentScore(intent, anchor, history);
        double technicalTermScore = technicalTermScore(anchor, history, userMessage);
        return (intentScore * 0.45) + (technicalTermScore * 0.55);
    }

    private double questionIntentScore(QuestionIntent intent, SubtopicAnchor anchor, List<ChatMessage> history) {
        if (intent == QuestionIntent.OTHER) {
            return 0.0;
        }

        String branchContext = normalizeForMatch(String.join(" ", buildAnchorProfileSamples(anchor, history)));
        if (branchContext.isBlank()) {
            return 0.0;
        }

        int matched = 0;
        for (String cue : intent.cues()) {
            String normalizedCue = normalizeForMatch(cue);
            if (!normalizedCue.isBlank() && branchContext.contains(normalizedCue)) {
                matched++;
            }
        }
        return matched / (double) Math.max(intent.cues().size(), 1);
    }

    private double technicalTermScore(SubtopicAnchor anchor, List<ChatMessage> history, String userMessage) {
        Set<String> queryTerms = extractTechnicalTerms(userMessage);
        if (queryTerms.isEmpty()) {
            return 0.0;
        }

        Set<String> branchKeywords = buildAnchorProfileKeywords(anchor, history);
        if (branchKeywords.isEmpty()) {
            return 0.0;
        }

        int matched = 0;
        for (String term : queryTerms) {
            if (branchKeywords.contains(term)) {
                matched++;
            }
        }
        return matched / (double) Math.max(queryTerms.size(), 1);
    }

    private Set<String> extractTechnicalTerms(String userMessage) {
        LinkedHashSet<String> technicalTerms = new LinkedHashSet<>();
        for (String token : extractKeywords(userMessage)) {
            boolean hasDigit = token.chars().anyMatch(Character::isDigit);
            boolean looksLikeAcronym = token.matches("[a-z]{2,6}");
            if (token.length() >= 3 || hasDigit || looksLikeAcronym) {
                technicalTerms.add(token);
            }
        }
        if (technicalTerms.isEmpty()) {
            technicalTerms.addAll(extractKeywords(userMessage));
        }
        return technicalTerms;
    }

    private boolean containsAnyCue(String normalizedText, List<String> cues) {
        if (normalizedText == null || normalizedText.isBlank() || cues == null || cues.isEmpty()) {
            return false;
        }
        for (String cue : cues) {
            String normalizedCue = normalizeForMatch(cue);
            if (!normalizedCue.isBlank() && normalizedText.contains(normalizedCue)) {
                return true;
            }
        }
        return false;
    }

    private double centroidAnchorScore(SubtopicAnchor anchor, List<ChatMessage> history, String userMessage) {
        return contextSimilarityService.centroidScore(userMessage, buildAnchorProfileSamples(anchor, history));
    }

    private Set<String> buildAnchorProfileKeywords(SubtopicAnchor anchor, List<ChatMessage> history) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        keywords.addAll(extractKeywords(anchor.topic()));
        keywords.addAll(extractKeywords(anchor.hints()));

        for (ChatMessage message : history) {
            if (message.getSender() != SenderRole.USER || !isAnchorProfileDepth(message)) {
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

    private List<String> buildAnchorProfileSamples(SubtopicAnchor anchor, List<ChatMessage> history) {
        LinkedHashSet<String> samples = new LinkedHashSet<>();
        addIfNotBlank(samples, anchor.topic());
        addIfNotBlank(samples, anchor.hints());

        for (ChatMessage message : history) {
            if (!isAnchorProfileDepth(message)) {
                continue;
            }
            if (!isDescendantOf(message, anchor.aiNode())) {
                continue;
            }

            if (message.getSender() == SenderRole.USER) {
                addIfNotBlank(samples, message.getNodeTitle());
                addIfNotBlank(samples, message.getLevel2Topic());
                addIfNotBlank(samples, stripSystemPrefix(message.getContent()));
            } else {
                addIfNotBlank(samples, stripSystemPrefix(message.getContent()));
            }
        }

        return samples.stream().limit(18).toList();
    }

    private boolean isAnchorProfileDepth(ChatMessage message) {
        return message != null && message.getDepth() >= 1 && message.getDepth() <= 2;
    }

    private ChatMessage chooseParentWithinAnchor(
            SubtopicAnchor anchor,
            ChatMessage requestedParent,
            List<ChatMessage> history,
            String userMessage
    ) {
        ChatMessage anchorBestParent = selectRelevantParentWithinAnchor(anchor, history, userMessage);

        if (requestedParent == null) {
            return anchorBestParent;
        }
        if (!isDescendantOf(requestedParent, anchor.aiNode())) {
            return anchorBestParent;
        }

        if (isSeriesSiblingRequest(requestedParent, userMessage)) {
            return resolveSiblingParent(requestedParent, anchor.aiNode());
        }

        if (hasExplicitSiblingIntent(userMessage)) {
            return resolveSiblingParent(requestedParent, anchor.aiNode());
        }

        if (isContinuationOfCurrentBranch(requestedParent, userMessage)
                || isLikelyChildExpansion(requestedParent, userMessage)) {
            if (anchorBestParent != null
                    && isDescendantOf(anchorBestParent, requestedParent)
                    && anchorBestParent.getDepth() >= requestedParent.getDepth()) {
                return anchorBestParent;
            }
            return requestedParent;
        }

        if (shouldRebalanceParent(anchorBestParent, requestedParent, history, userMessage)) {
            return anchorBestParent;
        }

        return requestedParent;
    }

    private boolean shouldRebalanceParent(
            ChatMessage candidateParent,
            ChatMessage requestedParent,
            List<ChatMessage> history,
            String userMessage
    ) {
        ChatMessage candidateAi = toAiNode(candidateParent);
        ChatMessage currentAi = toAiNode(requestedParent);

        if (candidateAi == null || currentAi == null || isSameNode(candidateAi, currentAi)) {
            return false;
        }

        if (isDescendantOf(candidateAi, currentAi)) {
            return false;
        }

        double candidateScore = computeParentRelevanceScore(candidateAi, history, userMessage);
        double currentScore = computeParentRelevanceScore(currentAi, history, userMessage);
        return candidateScore >= currentScore + PARENT_REBALANCE_SCORE_GAP_THRESHOLD;
    }

    private ChatMessage toAiNode(ChatMessage node) {
        if (node == null) {
            return null;
        }
        if (node.getSender() == SenderRole.AI) {
            return node;
        }
        return node.getParent();
    }

    private boolean isSameNode(ChatMessage left, ChatMessage right) {
        return left != null
                && right != null
                && left.getId() != null
                && left.getId().equals(right.getId());
    }

    private ChatMessage selectRelevantParentWithinAnchor(SubtopicAnchor anchor, List<ChatMessage> history, String userMessage) {
        if (anchor == null || anchor.aiNode() == null) {
            return null;
        }

        ChatMessage anchorAiNode = anchor.aiNode();
        List<ChatMessage> candidates = collectAnchorParentCandidates(anchor, history);
        if (candidates.isEmpty()) {
            return anchorAiNode;
        }

        double anchorScore = computeParentRelevanceScore(anchorAiNode, history, userMessage);
        double bestScore = anchorScore;
        ChatMessage bestParent = anchorAiNode;

        for (ChatMessage candidate : candidates) {
            double candidateScore = computeParentRelevanceScore(candidate, history, userMessage);
            if (candidateScore > bestScore) {
                bestScore = candidateScore;
                bestParent = candidate;
            }
        }

        if (bestParent == null) {
            return anchorAiNode;
        }
        if (bestParent.getId() != null
                && anchorAiNode.getId() != null
                && bestParent.getId().equals(anchorAiNode.getId())) {
            return anchorAiNode;
        }
        if (bestScore >= anchorScore + ANCHOR_PARENT_SCORE_GAP_THRESHOLD) {
            return bestParent;
        }
        return anchorAiNode;
    }

    private List<ChatMessage> collectAnchorParentCandidates(SubtopicAnchor anchor, List<ChatMessage> history) {
        if (anchor == null || anchor.aiNode() == null) {
            return List.of();
        }

        LinkedHashMap<Long, ChatMessage> deduplicated = new LinkedHashMap<>();
        ChatMessage anchorAiNode = anchor.aiNode();
        if (anchorAiNode.getId() != null) {
            deduplicated.put(anchorAiNode.getId(), anchorAiNode);
        }

        if (history == null || history.isEmpty()) {
            return new ArrayList<>(deduplicated.values());
        }

        for (ChatMessage message : history) {
            if (message.getSender() != SenderRole.AI || message.getId() == null) {
                continue;
            }
            if (!isDescendantOf(message, anchorAiNode)) {
                continue;
            }
            deduplicated.putIfAbsent(message.getId(), message);
        }

        return new ArrayList<>(deduplicated.values());
    }

    private double computeParentRelevanceScore(ChatMessage candidateAi, List<ChatMessage> history, String userMessage) {
        if (candidateAi == null) {
            return Double.NEGATIVE_INFINITY;
        }

        List<String> samples = new ArrayList<>();
        addIfNotBlank(samples, stripSystemPrefix(candidateAi.getContent()));

        ChatMessage userParent = candidateAi.getParent();
        if (userParent != null) {
            addIfNotBlank(samples, userParent.getNodeTitle());
            addIfNotBlank(samples, userParent.getLevel2Topic());
            addIfNotBlank(samples, stripSystemPrefix(userParent.getContent()));
            addIfNotBlank(samples, userParent.getTopicHints());
        }

        if (history != null && !history.isEmpty() && candidateAi.getId() != null) {
            int childSamples = 0;
            for (ChatMessage message : history) {
                if (message.getSender() != SenderRole.USER || message.getParent() == null || message.getParent().getId() == null) {
                    continue;
                }
                if (!candidateAi.getId().equals(message.getParent().getId())) {
                    continue;
                }
                addIfNotBlank(samples, message.getNodeTitle());
                addIfNotBlank(samples, stripSystemPrefix(message.getContent()));
                childSamples++;
                if (childSamples >= 4) {
                    break;
                }
            }
        }

        double relationshipScore = contextSimilarityService.relationshipScore(userMessage, samples);
        double carryoverScore = topicCarryoverScore(userMessage, samples);
        return (relationshipScore * 0.74) + (carryoverScore * 0.26);
    }

    private boolean hasExplicitSiblingIntent(String userMessage) {
        return userMessage != null && SIBLING_INTENT_PATTERN.matcher(userMessage).find();
    }

    private boolean isLikelyChildExpansion(ChatMessage requestedParent, String userMessage) {
        if (requestedParent == null || userMessage == null || userMessage.isBlank()) {
            return false;
        }
        if (hasExplicitSiblingIntent(userMessage)) {
            return false;
        }

        ChatMessage currentUserNode = requestedParent.getSender() == SenderRole.AI
                ? requestedParent.getParent()
                : requestedParent;
        if (currentUserNode == null) {
            return false;
        }

        List<String> currentSamples = new ArrayList<>();
        addIfNotBlank(currentSamples, currentUserNode.getNodeTitle());
        addIfNotBlank(currentSamples, currentUserNode.getLevel2Topic());
        addIfNotBlank(currentSamples, stripSystemPrefix(currentUserNode.getContent()));

        double carryoverScore = topicCarryoverScore(userMessage, currentSamples);
        boolean hasChildCue = CHILD_EXPANSION_PATTERN.matcher(userMessage).find();

        String currentText = defaultString(currentUserNode.getNodeTitle()) + " " + defaultString(currentUserNode.getContent());
        boolean incomingIndexed = extractSeriesMarker(userMessage) != null;
        boolean currentIndexed = extractSeriesMarker(currentText) != null;

        if (carryoverScore >= CHILD_EXPANSION_TOPIC_CARRYOVER_THRESHOLD && hasChildCue) {
            return true;
        }
        return carryoverScore >= INDEXED_CHILD_TOPIC_CARRYOVER_THRESHOLD && incomingIndexed && !currentIndexed;
    }

    private ChatMessage resolveSiblingParent(ChatMessage requestedParent, ChatMessage anchorAiNode) {
        if (requestedParent == null) {
            return anchorAiNode;
        }

        ChatMessage currentAiNode = requestedParent;
        if (currentAiNode.getSender() == SenderRole.USER) {
            currentAiNode = currentAiNode.getParent();
        }
        if (currentAiNode == null) {
            return anchorAiNode;
        }

        ChatMessage currentUserNode = currentAiNode.getParent();
        if (currentUserNode == null) {
            return anchorAiNode;
        }

        ChatMessage siblingParent = currentUserNode.getParent();
        if (siblingParent != null
                && siblingParent.getSender() == SenderRole.AI
                && isDescendantOf(siblingParent, anchorAiNode)) {
            return siblingParent;
        }
        return anchorAiNode;
    }

    private boolean isSeriesSiblingRequest(ChatMessage requestedParent, String userMessage) {
        if (requestedParent == null || userMessage == null || userMessage.isBlank()) {
            return false;
        }

        ChatMessage currentUserNode = requestedParent.getSender() == SenderRole.AI
                ? requestedParent.getParent()
                : requestedParent;
        if (currentUserNode == null) {
            return false;
        }

        String currentText = defaultString(currentUserNode.getNodeTitle()) + " " + defaultString(currentUserNode.getContent());
        if (hasDifferentSeriesIndex(currentText, userMessage)) {
            return true;
        }
        if (hasAcronymVariantSibling(currentText, userMessage)) {
            return true;
        }
        return hasSiblingIntentWithSharedTopic(currentText, userMessage);
    }

    private boolean hasDifferentSeriesIndex(String currentText, String userMessage) {
        SeriesMarker current = extractSeriesMarker(currentText);
        SeriesMarker incoming = extractSeriesMarker(userMessage);
        if (current == null || incoming == null) {
            return false;
        }
        boolean sameBase = current.base().equals(incoming.base())
                || current.base().contains(incoming.base())
                || incoming.base().contains(current.base());
        return sameBase && current.index() != incoming.index();
    }

    private SeriesMarker extractSeriesMarker(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        String[] tokens = lowered.split("[^\\p{L}\\p{N}]+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isBlank()) {
                continue;
            }

            Matcher combinedMatcher = SERIES_COMBINED_TOKEN_PATTERN.matcher(token);
            if (combinedMatcher.matches()) {
                String prefix = defaultString(combinedMatcher.group(1));
                String indexText = defaultString(combinedMatcher.group(2));
                String suffix = defaultString(combinedMatcher.group(3));
                if (!indexText.isBlank() && (!prefix.isBlank() || !suffix.isBlank())) {
                    String base = normalizeSeriesBase(prefix + suffix);
                    if (!base.isBlank()) {
                        return new SeriesMarker(base, Integer.parseInt(indexText));
                    }
                }
            }

            if (token.chars().allMatch(Character::isDigit) && i + 1 < tokens.length) {
                String next = normalizeSeriesBase(tokens[i + 1]);
                if (!next.isBlank()) {
                    return new SeriesMarker(next, Integer.parseInt(token));
                }
            }
        }
        return null;
    }

    private String normalizeSeriesBase(String base) {
        String normalized = normalizeForMatch(base);
        if (normalized.startsWith("\uC81C")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized
                .replaceFirst("^part", "")
                .replaceFirst("^step", "")
                .replaceFirst("^section", "")
                .replaceFirst("^chapter", "")
                .replaceFirst("^level", "")
                .replaceFirst("^no", "")
                .replaceFirst("^num", "");
        return normalized;
    }

    private boolean hasAcronymVariantSibling(String currentText, String userMessage) {
        Set<String> currentAcronyms = extractAcronymTokens(currentText);
        Set<String> incomingAcronyms = extractAcronymTokens(userMessage);
        if (currentAcronyms.isEmpty() || incomingAcronyms.isEmpty()) {
            return false;
        }

        for (String current : currentAcronyms) {
            for (String incoming : incomingAcronyms) {
                if (current.equals(incoming)) {
                    continue;
                }
                if (current.length() == incoming.length() && levenshteinDistance(current, incoming) == 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasSiblingIntentWithSharedTopic(String currentText, String userMessage) {
        if (!SIBLING_INTENT_PATTERN.matcher(userMessage).find()) {
            return false;
        }

        Set<String> currentKeywords = extractTechnicalTerms(currentText);
        Set<String> incomingKeywords = extractTechnicalTerms(userMessage);
        if (currentKeywords.isEmpty() || incomingKeywords.isEmpty()) {
            return false;
        }

        int overlap = 0;
        for (String keyword : incomingKeywords) {
            if (currentKeywords.contains(keyword)) {
                overlap++;
            }
        }
        return overlap >= 1;
    }

    private Set<String> extractAcronymTokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        LinkedHashSet<String> acronyms = new LinkedHashSet<>();
        for (String token : extractKeywords(text)) {
            if (token.matches("[a-z]{2,8}")) {
                acronyms.add(token);
            }
        }
        return acronyms;
    }

    private int levenshteinDistance(String left, String right) {
        int[][] dp = new int[left.length() + 1][right.length() + 1];
        for (int i = 0; i <= left.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= right.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[left.length()][right.length()];
    }

    private double keywordOverlapScore(SubtopicAnchor anchor, List<ChatMessage> history, String userMessage) {
        Set<String> messageKeywords = extractKeywords(userMessage);
        if (messageKeywords.isEmpty()) {
            return 0.0;
        }

        Set<String> profileKeywords = buildAnchorProfileKeywords(anchor, history);
        if (profileKeywords.isEmpty()) {
            return 0.0;
        }

        int matched = 0;
        for (String keyword : messageKeywords) {
            if (profileKeywords.contains(keyword)) {
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
        boolean shortQuery = normalizedMessage.length() <= 14;
        double threshold = shortQuery ? SHORT_QUERY_RELATIONSHIP_THRESHOLD : DEFAULT_RELATIONSHIP_THRESHOLD;
        if (relationshipScore >= threshold) {
            return true;
        }

        double carryoverScore = topicCarryoverScore(userMessage, contextSamples);
        return shortQuery
                && !hasExplicitSiblingIntent(userMessage)
                && carryoverScore >= SHORT_QUERY_TOPIC_CARRYOVER_THRESHOLD;
    }

    private double topicCarryoverScore(String userMessage, Collection<String> contextSamples) {
        Set<String> queryTerms = extractTechnicalTerms(userMessage);
        if (queryTerms.isEmpty() || contextSamples == null || contextSamples.isEmpty()) {
            return 0.0;
        }

        LinkedHashSet<String> contextTerms = new LinkedHashSet<>();
        for (String sample : contextSamples) {
            contextTerms.addAll(extractTechnicalTerms(sample));
        }
        if (contextTerms.isEmpty()) {
            return 0.0;
        }

        int matched = 0;
        for (String term : queryTerms) {
            if (contextTerms.contains(term)) {
                matched++;
            }
        }
        return matched / (double) Math.max(queryTerms.size(), 1);
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
                        .append(String.join(" / ", buildAnchorProfileSamples(anchor, history).stream().limit(4).toList()))
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

    private void ensureNodeTopicHints(ChatMessage node, List<ChatMessage> history) {
        if (node == null || node.getSender() != SenderRole.USER) {
            return;
        }

        if (node.getTopicHints() != null && !node.getTopicHints().isBlank()) {
            return;
        }

        String nodeTopic = extractNodeHintTopic(node);
        if (nodeTopic.isBlank()) {
            return;
        }

        List<String> siblingTopics = collectSiblingTopicsForNode(history, node);
        String hints = generateSubtopicHints(node.getLevel1Topic(), nodeTopic, siblingTopics);
        node.updateTopicHints(hints);
    }

    private List<String> collectSiblingTopicsForNode(List<ChatMessage> history, ChatMessage current) {
        if (history == null || history.isEmpty() || current == null) {
            return List.of();
        }

        Long parentId = current.getParent() != null ? current.getParent().getId() : null;
        List<String> siblings = new ArrayList<>();
        for (ChatMessage message : history) {
            if (message.getSender() != SenderRole.USER || message.getDepth() != current.getDepth()) {
                continue;
            }

            Long messageParentId = message.getParent() != null ? message.getParent().getId() : null;
            if (!Objects.equals(messageParentId, parentId)) {
                continue;
            }

            String siblingTopic = extractNodeHintTopic(message);
            if (!siblingTopic.isBlank()) {
                siblings.add(siblingTopic);
            }
        }
        return siblings;
    }

    private String extractNodeHintTopic(ChatMessage message) {
        if (message == null) {
            return "";
        }
        if (message.getNodeTitle() != null && !message.getNodeTitle().isBlank()) {
            return message.getNodeTitle().trim();
        }
        if (message.getDepth() <= 1 && message.getLevel2Topic() != null && !message.getLevel2Topic().isBlank()) {
            return message.getLevel2Topic().trim();
        }

        String stripped = stripSystemPrefix(defaultString(message.getContent()));
        if (!stripped.isBlank()) {
            return trimToLength(stripped, 40);
        }
        if (message.getLevel2Topic() != null && !message.getLevel2Topic().isBlank()) {
            return message.getLevel2Topic().trim();
        }
        if (message.getLevel1Topic() != null && !message.getLevel1Topic().isBlank()) {
            return message.getLevel1Topic().trim();
        }
        return "";
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

    private void addIfNotBlank(Collection<String> values, String value) {
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

    private String buildReason(
            boolean strongMatch,
            double hintScore,
            double keywordScore,
            double similarityScore,
            double centroidScore,
            double questionTypeTermScore,
            boolean aiSelected,
            QuestionIntent intent
    ) {
        return "strong=" + strongMatch
                + ", hints=" + round(hintScore)
                + ", keywords=" + round(keywordScore)
                + ", similarity=" + round(similarityScore)
                + ", centroid=" + round(centroidScore)
                + ", qTypeTerm=" + round(questionTypeTermScore)
                + ", intent=" + intent.name().toLowerCase(Locale.ROOT)
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

    private record ParentResolution(ChatMessage parentNode, boolean needsReevaluation) {
    }

    private record SubtopicRanking(SubtopicAnchor bestAnchor, String reason, double bestScore, double margin) {
    }

    private record SeriesMarker(String base, int index) {
    }

    private enum QuestionIntent {
        DEFINITION(List.of(
                "definition",
                "concept",
                "overview",
                "introduce",
                "what is",
                "\uAC1C\uB150",
                "\uC815\uC758",
                "\uAC1C\uC694",
                "\uC124\uBA85",
                "\uC54C\uB824\uC918"
        )),
        COMPARISON(List.of(
                "compare",
                "comparison",
                "difference",
                "vs",
                "versus",
                "\uBE44\uAD50",
                "\uCC28\uC774",
                "\uAD6C\uBD84"
        )),
        PROCEDURE(List.of(
                "how",
                "method",
                "process",
                "step",
                "implement",
                "build",
                "\uBC29\uBC95",
                "\uC808\uCC28",
                "\uAD6C\uD604",
                "\uC124\uACC4"
        )),
        EXAMPLE(List.of(
                "example",
                "sample",
                "case",
                "\uC608\uC2DC",
                "\uC0AC\uB840"
        )),
        CAUSAL(List.of(
                "why",
                "cause",
                "reason",
                "\uC65C",
                "\uC6D0\uC778",
                "\uC774\uC720"
        )),
        OTHER(List.of());

        private final List<String> cues;

        QuestionIntent(List<String> cues) {
            this.cues = cues;
        }

        private List<String> cues() {
            return cues;
        }
    }
}
