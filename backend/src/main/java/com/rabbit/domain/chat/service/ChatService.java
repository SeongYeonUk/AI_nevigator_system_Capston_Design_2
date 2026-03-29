package com.rabbit.domain.chat.service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import com.rabbit.domain.chat.Repository.ChatMessageRepository;
import com.rabbit.domain.chat.Repository.ChatRoomRepository;
import com.rabbit.domain.chat.dto.ChatHistoryResponse;
import com.rabbit.domain.chat.dto.ChatResponse;
import com.rabbit.domain.chat.dto.ChatRoomResponse;
import com.rabbit.domain.chat.dto.ConversationSummaryItemResponse;
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

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final ConversationInsightSummaryService conversationInsightSummaryService;
    private final Map<Long, AtomicInteger> roomTreeProcessingCounters = new ConcurrentHashMap<>();

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
        // 1. 권한 및 대화방 검증
        validateAuthorization(authorization);

        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Chat room not found."));

        // 2. 요청받은 부모 노드 (아직 진짜 부모인지 모름, 임시 위치)
        ChatMessage requestedParent = (parentId != null)
                ? chatMessageRepository.findById(parentId).orElse(null)
                : null;

        int initialDepth = (requestedParent == null) ? 0 : requestedParent.getDepth() + 1;
        String fallbackNodeTitle = trimToLength(defaultString(userMessage).replaceAll("\\s+", " "), 24);

        // 3. 유저 질문 임시 저장
        ChatMessage userSaved = chatMessageRepository.save(ChatMessage.builder()
                .chatRoom(room)
                .parent(requestedParent)
                .sender(SenderRole.USER)
                .content(userMessage)
                .nodeTitle(fallbackNodeTitle)
                .depth(initialDepth)
                .build());

        // 🌟 4. [초고속 처리] AI 답변만 즉시 생성!
        String aiAnswer = rabbitGuardService.chat(roomId, userMessage);

        // 5. AI 답변 임시 저장
        ChatMessage aiSaved = chatMessageRepository.save(ChatMessage.builder()
                .chatRoom(room)
                .parent(userSaved)
                .sender(SenderRole.AI)
                .content(aiAnswer)
                .depth(initialDepth)
                .build());

        // =====================================================================
        // 🗑️ 기존에 있던 무거운 라우팅 로직(history 가져오고, GPT 부르고 등등)은 전부 삭제!
        // =====================================================================

        markTreeProcessingStarted(roomId);
        triggerTreePostProcessingAsync(roomId, parentId, userMessage, userSaved.getId(), aiSaved.getId());

        return ChatResponse.builder()
                .answer(aiAnswer)
                .newNodeId(aiSaved.getId())
                .resolvedParentId(parentId)
                .nodeTitle(fallbackNodeTitle)
                .level1Topic("")
                .level2Topic("")
                .depth(initialDepth)
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
                    } finally {
                        markTreeProcessingFinished(roomId);
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

    private void markTreeProcessingStarted(Long roomId) {
        roomTreeProcessingCounters
                .computeIfAbsent(roomId, key -> new AtomicInteger(0))
                .incrementAndGet();
    }

    private void markTreeProcessingFinished(Long roomId) {
        roomTreeProcessingCounters.compute(roomId, (key, counter) -> {
            if (counter == null) {
                return null;
            }
            int next = counter.decrementAndGet();
            return next > 0 ? counter : null;
        });
    }

    private boolean isTreeProcessing(Long roomId) {
        AtomicInteger counter = roomTreeProcessingCounters.get(roomId);
        return counter != null && counter.get() > 0;
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

        String aiAnswer = aiSaved.getContent();
        String aiHint = aiAnswer.substring(0, Math.min(aiAnswer.length(), 100)).replace("\n", " ");
        String contextForRouting = userMessage + " (AI 답변 힌트: " + aiHint + ")";

        // userMessage 대신 contextForRouting을 넣어줍니다!
        ChatMessage parentNode = resolveParentNodeForIntent(historyBeforeCurrent, contextForRouting);
        int currentDepth = (parentNode == null) ? 0 : parentNode.getDepth() + 1;

        ConversationTreePlannerService.TreePlan treePlan = conversationTreePlannerService.planNode(
                historyBeforeCurrent,
                parentNode,
                currentDepth,
                contextForRouting
        );

        userSaved.updateTreePlacement(parentNode, currentDepth);
        userSaved.updateTreeMetadata(treePlan.nodeTitle(), treePlan.level1Topic(), treePlan.level2Topic());
        ensureNodeTopicHints(userSaved, historyBeforeCurrent);
        aiSaved.updateDepth(currentDepth);

        if (currentDepth == 0) {
            createInitialLevelTwoSeedNodes(room, aiSaved, treePlan.level1Topic(), userMessage);
        }

        // applyTreePostProcessing 메서드 안에 임시 로그 추가
        log.info("💡 최종 결정된 부모 ID: {}, 새 노드의 Depth: {}",
                parentNode != null ? parentNode.getId() : "null", currentDepth);
    }

    private ChatMessage resolveParentNodeForIntent(List<ChatMessage> history, String userMessage) {
        if (history == null || history.isEmpty()) return null;

        ChatMessage lastAiNode = history.stream()
                .filter(m -> m.getSender() == SenderRole.AI)
                .max(Comparator.comparing(ChatMessage::getCreatedAt))
                .orElse(null);

        List<SubtopicAnchor> anchors = findSubtopicAnchors(history);

        // 🌟 [추가] 현재 대화 중인 기둥(Anchor)이 무엇인지 먼저 찾습니다.
        SubtopicAnchor currentAnchor = findAnchorForAiNode(lastAiNode, anchors);

        // 🌟 [수정] 인자를 4개(anchors, history, userMessage, currentAnchor) 보냅니다.
        SubtopicAnchor bestAnchor = rankSubtopicAnchors(anchors, history, userMessage, currentAnchor);

        if (bestAnchor == null) return lastAiNode;
        return chooseParentWithinAnchor(bestAnchor, lastAiNode, history, userMessage);
    }

    // (헬퍼 메서드 추가) 특정 AI 노드가 속한 앵커 기둥 찾기
    // (헬퍼 메서드 추가) 특정 AI 노드가 속한 앵커 기둥 찾기
    private SubtopicAnchor findAnchorForAiNode(ChatMessage aiNode, List<SubtopicAnchor> anchors) {
        if (aiNode == null || anchors == null || anchors.isEmpty()) return null;
        for (SubtopicAnchor anchor : anchors) {
            if (isDescendantOf(aiNode, anchor.aiNode())) {
                return anchor;
            }
        }
        return null;
    }

    // 🌟 밸런스 패치 완료된 예선전 채점 로직
    private SubtopicAnchor rankSubtopicAnchors(
            List<SubtopicAnchor> anchors,
            List<ChatMessage> history,
            String userMessage,
            SubtopicAnchor currentActiveAnchor
    ) {
        if (anchors == null || anchors.isEmpty()) {
            return null;
        }

        // 🚨 "데드락이 뭐야?"에서 "뭐야"가 지워져도 원본 텍스트를 살려서 무조건 채점을 돌리게 만듭니다.
        String normalizedMessage = normalizeForMatch(userMessage);
        if (normalizedMessage.isBlank()) {
            normalizedMessage = userMessage.toLowerCase(Locale.ROOT).trim();
        }

        // 🌟 1. GPT 심판장에게 먼저 대주제(기둥)를 고르게 합니다.
        SubtopicAnchor aiSelectedAnchor = selectAnchorByAi(anchors, history, userMessage, currentActiveAnchor);

        double bestScore = Double.NEGATIVE_INFINITY;
        SubtopicAnchor bestAnchor = null;

        for (SubtopicAnchor anchor : anchors) {
            boolean isDirectTopicMatch = containsTopic(normalizedMessage, anchor.topic());

            // 🚨 키워드 추출 시 STOP_WORDS 때문에 다 지워지는 걸 방지하기 위해 원본 메시지도 넘김
            double hintScore = contextSimilarityService.hintOverlapScore(userMessage, branchDescriptor(anchor));

            // 키워드 겹침 점수 (너무 엄격하지 않게)
            double keywordScore = keywordOverlapScore(anchor, history, userMessage);
            if (keywordScore == 0.0 && isDirectTopicMatch) {
                keywordScore = 1.0;
            }

            double similarityScore = contextSimilarityService.score(
                    userMessage,
                    branchDescriptor(anchor),
                    buildAnchorProfileSamples(anchor, history)
            );
            double centroidScore = centroidAnchorScore(anchor, history, userMessage);

            boolean isAiSelected = aiSelectedAnchor != null && anchor.aiNode().getId().equals(aiSelectedAnchor.aiNode().getId());

            // 🌟 [핵심 로직] UI 포커스 가중치 계산
            double focusWeight = 0.0;
            if (currentActiveAnchor != null && anchor.aiNode().getId().equals(currentActiveAnchor.aiNode().getId())) {
                focusWeight = 30.0;
                log.info("📍 UI 포커스 감지: '{}' 기둥에 강력 가중치 부여", anchor.topic());
            }

            double totalScore = (isDirectTopicMatch ? 20.0 : 0.0)
                    + (hintScore * 5.0)
                    + (similarityScore * 10.0)
                    + (isAiSelected ? 200.0 : 0.0) // GPT의 논리적 판단을 절대적으로 신뢰
                    + focusWeight;

            log.info("Routing candidate topic='{}' similarity={} centroid={} aiSelected={} total={}",
                    anchor.topic(), round(similarityScore), round(centroidScore), isAiSelected, round(totalScore));

            if (totalScore > bestScore) {
                bestScore = totalScore;
                bestAnchor = anchor;
            }
        }

        return bestAnchor != null ? bestAnchor : anchors.get(0);
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

    /*
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

     */

    /*
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

     */

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
            SubtopicAnchor anchor, ChatMessage requestedParent, List<ChatMessage> history, String userMessage) {

        // 🌟 [비용 절감 핵심 로직 복구] 민교님 말씀대로 토큰을 아끼기 위해 정규식 문지기를 부활시킵니다!
        // 단, 오작동을 막기 위해 뒤에 붙은 "(AI 답변 힌트:...)" 부분을 잘라내고 순수 사용자 질문만 검사합니다.
        String pureUserMessage = userMessage.replaceAll("\\(AI 답변 힌트:.*\\)", "").trim();

        // 순수 질문에서 명백한 형제 이동 의도("다음 거", "다른 거")가 보이면 바로 LLM 건너뛰고 처리 (비용 Save 💰)
        if (isSeriesSiblingRequest(requestedParent, pureUserMessage) || hasExplicitSiblingIntent(pureUserMessage)) {
            log.info("⚡ 비용 절감: 정규식 문지기가 명확한 형제 이동(Sibling)을 감지했습니다.");
            return resolveSiblingParent(requestedParent, anchor.aiNode());
        }

        // 정규식으로 판별하기 어려운 복잡한 질문만 똑똑한 하이브리드 엔진(LLM + Vector)으로 넘깁니다.
        ChatMessage anchorBestParent = selectRelevantParentWithinAnchor(anchor, history, userMessage);

        if (anchorBestParent != null) {
            log.info("🤖 AI 라우팅 100% 신뢰 적용! 최종 부모: {}", anchorBestParent.getId());
            return anchorBestParent;
        }

        return requestedParent != null ? requestedParent : anchor.aiNode();
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

    //
    private ChatMessage selectRelevantParentWithinAnchor(SubtopicAnchor anchor, List<ChatMessage> history, String userMessage) {
        if (anchor == null || anchor.aiNode() == null) return null;

        List<ChatMessage> candidates = collectAnchorParentCandidates(anchor, history);
        if (candidates.isEmpty()) return anchor.aiNode();

        ChatMessage lastAiNode = history.stream()
                .filter(m -> m.getSender() == SenderRole.AI && isDescendantOf(m, anchor.aiNode()))
                .max(Comparator.comparing(ChatMessage::getCreatedAt))
                .orElse(anchor.aiNode());

        record ScoredNode(ChatMessage node, double score) {}
        List<ScoredNode> scoredNodes = new ArrayList<>();
        for (ChatMessage candidate : candidates) {
            double score = computeParentRelevanceScore(candidate, history, userMessage);
            scoredNodes.add(new ScoredNode(candidate, score));
        }

        scoredNodes.sort((a, b) -> Double.compare(b.score(), a.score()));
        Set<Long> topIds = new HashSet<>();
        List<ChatMessage> finalCandidates = new ArrayList<>();

        for (ScoredNode sn : scoredNodes) {
            if (finalCandidates.size() >= 5) break;
            finalCandidates.add(sn.node());
            topIds.add(sn.node().getId());
        }

        ChatMessage ancestor = lastAiNode;
        while (ancestor != null && finalCandidates.size() < 10) {
            if (!topIds.contains(ancestor.getId())) {
                finalCandidates.add(ancestor);
                topIds.add(ancestor.getId());
            }
            ancestor = getRealParent(ancestor);
            if (ancestor != null && ancestor.getDepth() == 0) break;
        }

        // 🌟 최상위 개념부터 하위 개념으로(Top-down) 정렬
        finalCandidates.sort(Comparator.comparingInt(ChatMessage::getDepth));

        log.info("🔥 GPT 결승전 라인업 (Top-down 정렬): {}", finalCandidates.stream()
                .map(n -> compactNodeTitle(n) + "(D" + n.getDepth() + ")")
                .collect(Collectors.joining(" -> ")));

        ChatMessage llmChoice = resolveBestParentWithLLM(userMessage, finalCandidates);

        return llmChoice != null ? llmChoice : finalCandidates.get(0);
    }


    // [헬퍼 메서드 추가] User 노드를 건너뛰고 진짜 AI 부모를 찾아주는 메서드
    private ChatMessage getRealParent(ChatMessage node) {
        if (node == null || node.getParent() == null) return null;
        ChatMessage parent = node.getParent();
        return parent.getSender() == SenderRole.USER ? parent.getParent() : parent;
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

    // [수정] 기존 메서드를 아래 내용으로 교체하세요
    // [수정] 기존 메서드 교체
    private double computeParentRelevanceScore(ChatMessage candidateAi, List<ChatMessage> history, String userMessage) {
        if (candidateAi == null) {
            return Double.NEGATIVE_INFINITY;
        }

        List<String> samples = new ArrayList<>();

        // 1. 전략 A: 전체 경로 문맥
        String pathContext = getPathContext(candidateAi);
        addIfNotBlank(samples, pathContext);
        addIfNotBlank(samples, stripSystemPrefix(candidateAi.getContent()));

        ChatMessage userParent = candidateAi.getParent();
        if (userParent != null) {
            addIfNotBlank(samples, userParent.getNodeTitle());
            addIfNotBlank(samples, userParent.getLevel2Topic());
            addIfNotBlank(samples, stripSystemPrefix(userParent.getContent()));
            addIfNotBlank(samples, userParent.getTopicHints());
        }

        // 2. 최근 자식 대화 문맥 추가
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
                if (childSamples >= 3) break;
            }
        }

        // 3. 순수 임베딩 점수 계산
        double relationshipScore = contextSimilarityService.relationshipScore(userMessage, samples);
        double carryoverScore = topicCarryoverScore(userMessage, samples);
        double baseScore = (relationshipScore * 0.80) + (carryoverScore * 0.20);

        // 🌟 [핵심 로직] 4. 위상(Topology) 기반 거리 페널티 적용
        int currentMaxDepth = history.stream()
                .filter(m -> m.getSender() == SenderRole.USER)
                .mapToInt(ChatMessage::getDepth)
                .max().orElse(0);

        int depthDifference = currentMaxDepth - candidateAi.getDepth();
        double depthPenalty = 0.0;

        if (depthDifference >= 2) {
            depthPenalty = depthDifference * -0.05; // 2칸 이상 차이나면 강력한 마이너스 점수!
        } else if (candidateAi.getDepth() > 0) {
            depthPenalty = candidateAi.getDepth() * 0.01; // 깊은 노드는 약간의 가산점
        }

        return baseScore + depthPenalty;
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
        if (requestedParent == null) return anchorAiNode;

        // 1. 현재 노드가 AI면 User로, User면 자기 자신
        ChatMessage currentUserNode = requestedParent.getSender() == SenderRole.AI
                ? requestedParent.getParent()
                : requestedParent;

        if (currentUserNode == null || currentUserNode.getParent() == null) return anchorAiNode;

        // 2. User 노드의 부모(AI)가 바로 '진짜 부모'입니다.
        ChatMessage siblingParent = currentUserNode.getParent();

        // 3. 그 진짜 부모가 현재 앵커(자료구조) 소속이라면 그 부모를 반환! (루트로 올리지 않음)
        if (siblingParent != null
                && siblingParent.getSender() == SenderRole.AI
                && isDescendantOf(siblingParent, anchorAiNode)) {

            log.info("정상 형제 처리: 부모 노드 {} 를 반환합니다.", siblingParent.getId());
            return siblingParent;
        }

        // 정 못 찾으면 앵커로 폴백
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
    // 🌟 [범용 AI 기둥 선택기] 하드코딩 꼼수 X, 순수 논리적 추론 O
    private SubtopicAnchor selectAnchorByAi(List<SubtopicAnchor> anchors, List<ChatMessage> history, String userMessage, SubtopicAnchor currentAnchor) {
        if (anchors.isEmpty()) return null;
        try {
            StringBuilder options = new StringBuilder();
            for (int i = 0; i < anchors.size(); i++) {
                options.append(i + 1).append(") ").append(anchors.get(i).topic()).append("\n");
            }

            String currentContext = (currentAnchor != null) ? currentAnchor.topic() : "없음 (새로운 대화)";

            String prompt = String.format("""
                [Role]
                당신은 컴퓨터 공학 전공 지식을 분류하는 전문가입니다. 
                사용자의 질문을 분석하여, 아래 [카테고리 후보] 중 가장 알맞은 대주제(기둥)를 선택하세요.
                
                [Context]
                현재 활성화된 대주제: %s
                
                [User Question]
                "%s"
                
                [카테고리 후보]
                %s
                
                [Reasoning Steps (필수)]
                1. 주어 파악: 질문의 핵심 CS 개념을 파악하세요. 만약 질문이 짧거나 주어가 생략되었다면(예: "발생조건은?", "분류해봐"), 무조건 [Context]의 대주제와 관련된 질문으로 간주하세요.
                2. 학문 매칭: 해당 개념이 컴퓨터 공학의 어느 전공 과목(OS, DB, 자료구조 등)에서 주로 다루는지 추론하세요.
                3. 최종 선택: 추론한 전공과 가장 일치하는 번호를 후보에서 고르세요.
                
                [Output Format]
                반드시 아래 JSON 형식으로만 응답하세요. 마크다운(```json) 포함 금지.
                {
                  "reasoning": "1~2단계 추론 과정을 1줄로 요약",
                  "selectedIndex": 정답숫자
                }
                """, currentContext, userMessage, options.toString());

            String rawAnswer = conversationTreeAiService.selectBestSubtopic(prompt);

            int start = rawAnswer.indexOf("{");
            int end = rawAnswer.lastIndexOf("}");
            if (start != -1 && end != -1) {
                String jsonStr = rawAnswer.substring(start, end + 1);
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> result = mapper.readValue(jsonStr, new TypeReference<Map<String, Object>>(){});

                int chosenIdx = Integer.parseInt(result.get("selectedIndex").toString()) - 1;
                String reasoning = result.get("reasoning").toString();

                log.info("🎯 GPT 예선전(기둥) 판결: {}번 후보 선택 (이유: {})", chosenIdx + 1, reasoning);

                if (chosenIdx >= 0 && chosenIdx < anchors.size()) {
                    return anchors.get(chosenIdx);
                }
            }
        } catch (Exception e) {
            log.warn("AI anchor selection failed (Fallback to Vector): {}", e.getMessage());
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

    // 🌟 1. UI와 DB에 저장되는 이름표는 다시 원래의 "예쁜 요약본"으로 되돌립니다.
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
                .processing(isTreeProcessing(roomId))
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
        String titleSource = resolveInsightTitle(node);
        List<ConversationSummaryItemResponse> conversationSummary = conversationInsightSummaryService.summarize(node);

        return NodeInsightResponse.builder()
                .title(titleSource.substring(0, Math.min(titleSource.length(), 24)))
                .depth(node.getDepth())
                .parentPath(parentPath)
                .progressRatio(ratio)
                .alertMessage(node.getDepth() >= 5 ? "Warning: depth is high." : "Stable.")
                .conversationSummary(conversationSummary)
                .build();
    }

    private String resolveInsightTitle(ChatMessage node) {
        if (node == null) {
            return "";
        }

        if (node.getSender() == SenderRole.AI) {
            ChatMessage userNode = node.getParent();
            if (userNode != null) {
                if (userNode.getNodeTitle() != null && !userNode.getNodeTitle().isBlank()) {
                    return userNode.getNodeTitle().trim();
                }
                String userContent = stripSystemPrefix(defaultString(userNode.getContent()));
                if (!userContent.isBlank()) {
                    return userContent;
                }
            }
        }

        if (node.getNodeTitle() != null && !node.getNodeTitle().isBlank()) {
            return node.getNodeTitle().trim();
        }

        String content = stripSystemPrefix(defaultString(node.getContent()));
        return content.isBlank() ? "Untitled" : content;
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

    // [새로 추가] 특정 노드부터 상위로 올라가며 경로(Path)를 텍스트로 합치는 메서드
    private String getPathContext(ChatMessage node) {
        if (node == null) return "";
        List<String> pathTitles = new ArrayList<>();
        ChatMessage current = node;

        int maxDepth = 4; // 너무 깊은 탐색 방지
        while (current != null && maxDepth-- > 0) {
            String title = extractNodeHintTopic(current);
            if (!title.isBlank()) {
                pathTitles.add(title);
            }
            current = current.getParent();
        }

        Collections.reverse(pathTitles); // 루트 -> 하위 순서로 정렬
        return String.join(" -> ", pathTitles);
    }

    private ChatMessage resolveBestParentWithLLM(String userMessage, List<ChatMessage> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        try {
            // 🚨 [수정 1] AI 힌트에 GPT가 낚이는 것을 방지하기 위해 괄호 안의 힌트를 제거하고 순수 질문만 추출합니다!
            String pureTargetQuestion = userMessage.replaceAll("\\(AI 답변 힌트:.*\\)", "").trim();

            StringBuilder options = new StringBuilder("[\n");
            int maxDepth = -1;
            String deepestTopic = "";

            for (int i = 0; i < candidates.size(); i++) {
                ChatMessage c = candidates.get(i);

                // UI용 타이틀 (AI의 긴 답변일 수도 있고, 짧은 요약일 수도 있음)
                String cleanTitle = compactNodeTitle(c);
                cleanTitle = cleanTitle.replaceAll("\\[AUTO_SUBTOPIC(?:_ACK)?\\]", "").trim();
                cleanTitle = cleanTitle.replaceAll("[\n\r\"'\\\\]", " ");

                // 🚨 핵심: AI 노드의 부모(사용자)가 입력했던 "진짜 질문 원문"을 무조건 추출
                String userRealQuestion = "알 수 없음";
                ChatMessage userNode = c.getParent();
                if (userNode != null && userNode.getSender() == SenderRole.USER) {
                    userRealQuestion = stripSystemPrefix(defaultString(userNode.getContent()));
                    userRealQuestion = userRealQuestion.replaceAll("[\n\r\"'\\\\]", " ").trim();
                }

                if (c.getDepth() > maxDepth) {
                    maxDepth = c.getDepth();
                    // 문맥 파악용은 실제 유저 질문을 사용!
                    deepestTopic = userRealQuestion.equals("알 수 없음") ? cleanTitle : userRealQuestion;
                }

                // 🌟 JSON에 "user_question" 필드를 추가하여 GPT가 절대 오해하지 않도록 만듦!
                options.append(String.format("  {\"id\": %d, \"depth\": %d, \"topic\": \"%s\", \"user_question\": \"%s\"}",
                        c.getId(), c.getDepth(), trimToLength(cleanTitle, 30), trimToLength(userRealQuestion, 50)));
                if (i < candidates.size() - 1) options.append(",\n");
            }
            options.append("\n]");

            String prompt = String.format("""
            <System_Persona>
            당신은 전 세계 컴퓨터 공학 지식을 분류하는 '수석 지식 그래프 아키텍트'입니다.
            사용자의 질문(Target)을 후보군(Candidates Dataset) 중 가장 논리적인 '직계 부모(Immediate Parent)'에 연결하십시오.
            </System_Persona>
            
            <Ontology_Rules>
            1. [Taxonomic Membership]
               - Target 질문이 Candidate의 **'user_question'** 내용의 '구체적 종류/사례', '세부 속성', '다음 단계'를 묻는다면 90~100점 부여.
               - 🚨 주의: 'topic'보다 **'user_question'**을 기준으로 부모 자격을 판단하십시오!

            2. [Sibling & Sequence Exclusion (절대 규칙 🚨)]
               - Target 질문과 Candidate가 서로 대등한 병렬 관계이거나, 동일한 부모를 공유하는 연속된 단계(예: 제1정규형과 제2정규형)라면 무조건 0~20점을 부여하여 부모 후보에서 탈락시키십시오.

            3. [Contextual Inference for Short Queries]
               - Target에 주어가 없다면, 현재 대화의 가장 깊은 질문인 '%s'에 대한 후속 질문으로 간주하십시오.

            4. [Depth Priority (절대 규칙 🚨)]
               - 만약 Target이 여러 Candidate에 논리적으로 속할 수 있다면(예: Depth 1과 Depth 2), 무조건 '가장 구체적인 질문을 했던 가장 깊은 Depth' 후보에게 100점을 몰아주고, 얕은 Depth(포괄적 개념)는 50점 이하로 대폭 감점하십시오.
            </Ontology_Rules>
            
            <Input_Context>
            - Target Concept(User Question): "%s"
            - Candidates Dataset:
            %s
            </Input_Context>
            
            <Output_Constraint>
            JSON으로만 응답하십시오. (No Markdown)
            {
              "target_analysis": "Target 질문의 핵심 의도 및 부모 선택 논리 요약",
              "evaluations": [
                { "id": ID, "logic_rel": "Hyponym | Hypernym | Sibling | Irrelevant", "score": 점수, "reason": "룰 적용 근거 ('user_question' 기준)" }
              ]
            }
            </Output_Constraint>
            """, deepestTopic, pureTargetQuestion, options.toString()); // 👈 수정: pureTargetQuestion 적용

            String rawAnswer = conversationTreeAiService.selectBestSubtopic(prompt);
            int start = rawAnswer.indexOf("{");
            int end = rawAnswer.lastIndexOf("}");
            if (start == -1 || end == -1) return candidates.get(0);

            JsonNode rootNode = new ObjectMapper().readTree(rawAnswer.substring(start, end + 1));
            log.info("🧠 [Taxonomy Insight] {}", rootNode.path("target_analysis").asText());

            JsonNode evaluations = rootNode.path("evaluations");
            Long bestId = null;
            int highestScore = -1;
            int deepestDepth = -1;

            // 🌟 3. 점수 밸런스 패치 적용
            for (JsonNode eval : evaluations) {
                Long id = eval.path("id").asLong();
                int score = eval.path("score").asInt();

                int currentDepth = -1;
                for (ChatMessage c : candidates) {
                    if (c.getId().equals(id)) {
                        currentDepth = c.getDepth();
                        break;
                    }
                }


                // 🚨 [수정 2] 0점짜리가 뎁스빨로 우승하는 것을 원천 차단!
                // GPT가 70점 이상(논리적 부모로 인정)을 준 경우에만, 타이브레이커 느낌으로 깊이에 따른 소폭의 가산점(+5점)을 줍니다.
                int adjustedScore = score;
                if (score >= 70) {
                    adjustedScore = score + (currentDepth * 5);
                }

                log.info(" 🔍 Node [{}] | Logic: {} | Original Score: {} | Adjusted Score: {} | Depth: {} | Reason: {}",
                        id, eval.path("logic_rel").asText(), score, adjustedScore, currentDepth, eval.path("reason").asText());

                // 이제 '조정된 점수(adjustedScore)'로 챔피언을 가립니다!
                if (adjustedScore > highestScore) {
                    highestScore = adjustedScore;
                    bestId = id;
                    deepestDepth = currentDepth;
                }
            }

            if (bestId != null) {
                Long finalBestId = bestId;
                return candidates.stream().filter(c -> c.getId().equals(finalBestId)).findFirst().orElse(candidates.get(0));
            }
        } catch (Exception e) {
            log.error("Engineering Pipeline Error: {}", e.getMessage());
        }
        return candidates.get(0);
    }


    // 노드 및 하위 트리 전체 삭제
    @Transactional
    public void deleteNodeAndSubtree(String authorization, Long roomId, Long nodeId) {
        validateAuthorization(authorization);

        ChatMessage targetNode = chatMessageRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 노드입니다."));

        // 1. 하위 트리(자식들)를 바닥부터 싹 다 지웁니다.
        deleteChildrenRecursively(targetNode.getId());

        // 2. 마지막으로 자기 자신을 깔끔하게 삭제합니다.
        chatMessageRepository.delete(targetNode);
        log.info("노드 및 하위 트리 삭제 완료: {}", nodeId);
    }

    private void deleteChildrenRecursively(Long parentId) {
        List<ChatMessage> children = chatMessageRepository.findByParentId(parentId);
        for (ChatMessage child : children) {
            deleteChildrenRecursively(child.getId()); // 바닥 끝까지 파고들기
            chatMessageRepository.delete(child); // 밑에서부터 위로 차례대로 삭제
        }
    }

     // 노드 이동 (부모 변경 및 Depth 일괄 업데이트)
    @Transactional
    public void moveNode(String authorization, Long roomId, Long nodeId, Long newParentId) {
        validateAuthorization(authorization);

        if (nodeId.equals(newParentId)) {
            throw new IllegalArgumentException("자기 자신 밑으로 이동할 수 없습니다.");
        }

        ChatMessage sourceNode = chatMessageRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("이동할 노드를 찾을 수 없습니다."));

        ChatMessage targetNode = chatMessageRepository.findById(newParentId)
                .orElseThrow(() -> new IllegalArgumentException("새로운 부모 노드를 찾을 수 없습니다."));

        // 🚨 가장 중요한 방어 로직 (순환 참조 방지)
        // 내 자식이나 손자 밑으로 내가 들어가려고 하면 무한 루프에 빠집니다!
        if (isDescendant(nodeId, newParentId)) {
            throw new IllegalArgumentException("자신의 하위 노드로는 이동할 수 없습니다.");
        }

        // 1. 뎁스 차이 계산 (이사 갈 집의 뎁스 + 1 - 내 현재 뎁스)
        int nextDepth = targetNode.getDepth() + 1;
        int depthDiff = nextDepth - sourceNode.getDepth();

        // 2. 엔티티에 만들어두신 메서드 찰떡 활용! (부모와 뎁스 동시 변경)
        sourceNode.updateTreePlacement(targetNode, nextDepth);

        // 3. 내 밑에 딸려가는 자식들의 뎁스도 일괄적으로 싹 맞춰줍니다.
        if (depthDiff != 0) {
            updateChildrenDepthRecursively(sourceNode.getId(), depthDiff);
        }
        log.info("노드 이동 완료: {} -> 새 부모: {}", nodeId, newParentId);
    }

    private void updateChildrenDepthRecursively(Long parentId, int depthDiff) {
        List<ChatMessage> children = chatMessageRepository.findByParentId(parentId);
        for (ChatMessage child : children) {
            child.updateDepth(child.getDepth() + depthDiff);
            updateChildrenDepthRecursively(child.getId(), depthDiff);
        }
    }

    // 대상(targetId)이 나(parentId)의 핏줄(자손)인지 확인하는 헬퍼 메서드
    private boolean isDescendant(Long parentId, Long targetId) {
        List<ChatMessage> children = chatMessageRepository.findByParentId(parentId);
        for (ChatMessage child : children) {
            if (child.getId().equals(targetId)) return true; // 내 자식 중에 목적지가 있으면 컷!
            if (isDescendant(child.getId(), targetId)) return true; // 손자 증손자까지 탐색
        }
        return false;
    }




}
