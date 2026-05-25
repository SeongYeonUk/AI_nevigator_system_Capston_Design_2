package com.rabbit.domain.chat.service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import com.rabbit.domain.chat.Repository.ChatMessageRepository;
import com.rabbit.domain.chat.Repository.ChatRoomRepository;
import com.rabbit.domain.chat.dto.*;
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

    private static final Pattern RECOMMENDATION_LINE_SPLIT_PATTERN = Pattern.compile("[,\\n;|]+");
    private static final Pattern RECOMMENDATION_PREFIX_PATTERN = Pattern.compile("^\\s*[-*\\d.)\\s]+");
    private static final Pattern TRAILING_PARENT_CONTEXT_PATTERN = Pattern.compile("\\s*\\([^()]*\\)\\s*$");

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

    private static final Set<String> ROOT_TOPIC_CHECK_STOP_WORDS = Set.of(
            "그리고", "그러면", "그럼", "대한", "대해", "관련", "설명", "알려줘", "알려", "질문",
            "무엇", "뭐야", "어떻게", "왜", "해줘", "해주세요", "있는", "없는", "이번", "다음",
            "정리", "예시", "비교", "방법", "차이", "the", "and", "for", "with", "what", "how",
            "why", "about", "please", "more", "detail"
    );
    private static final double ROOT_TOPIC_UNRELATED_THRESHOLD = 0.30;
    private static final long ROOT_TOPIC_CHECK_CACHE_TTL_MS = 20_000L;

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
    private final Map<String, RootTopicCheckCacheEntry> rootTopicCheckCache = new ConcurrentHashMap<>();
    private final AiPreProcessorService aiPreProcessorService;

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

    @Transactional(readOnly = true)
    public RootTopicCheckResponse checkRootTopicRelation(
            String authorization,
            Long roomId,
            RootTopicCheckRequest request
    ) {
        validateAuthorization(authorization);
        chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Chat room not found."));

        String question = defaultString(request != null ? request.getMessage() : "");
        if (question.isBlank()) {
            return RootTopicCheckResponse.builder()
                    .unrelated(false)
                    .rootTopic("")
                    .similarity(1.0)
                    .message("")
                    .build();
        }

        List<ChatMessage> history = chatMessageRepository.findByChatRoomIdOrderByCreatedAtAsc(roomId);
        ChatMessage rootUser = history.stream()
                .filter(message -> message.getSender() == SenderRole.USER)
                .filter(message -> message.getDepth() == 0)
                .findFirst()
                .orElse(null);

        if (rootUser == null) {
            return RootTopicCheckResponse.builder()
                    .unrelated(false)
                    .rootTopic("")
                    .similarity(1.0)
                    .message("")
                    .build();
        }

        String cacheKey = buildRootTopicCheckCacheKey(roomId, request != null ? request.getParentId() : null, question);
        RootTopicCheckResponse cached = getCachedRootTopicCheck(cacheKey);
        if (cached != null) {
            return cached;
        }

        String rootTopic = defaultString(rootUser.getLevel1Topic()).isBlank()
                ? compactNodeTitle(rootUser)
                : rootUser.getLevel1Topic().trim();

        List<String> rootContext = buildRootTopicCheckContext(history, rootUser);
        List<String> parentPathContext = buildParentPathContext(request != null ? request.getParentId() : null);
        double rootSimilarity = topicRelationshipScore(question, rootContext);
        double pathSimilarity = topicRelationshipScore(question, parentPathContext);
        double bestSimilarity = Math.max(rootSimilarity, pathSimilarity);
        boolean aiUnrelated = classifyRootTopicUnrelated(question, rootTopic, rootContext);
        boolean unrelated = extractCheckTokens(question).size() >= 1
                && bestSimilarity < ROOT_TOPIC_UNRELATED_THRESHOLD
                && aiUnrelated;

        RootTopicCheckResponse response = RootTopicCheckResponse.builder()
                .unrelated(unrelated)
                .rootTopic(rootTopic)
                .similarity(Math.round(bestSimilarity * 1000.0) / 1000.0)
                .message(unrelated ? "대주제와 관계가 낮은 질문으로 보입니다." : "")
                .build();
        rootTopicCheckCache.put(cacheKey, new RootTopicCheckCacheEntry(response, System.currentTimeMillis()));
        return response;
    }

    private RootTopicCheckResponse getCachedRootTopicCheck(String cacheKey) {
        RootTopicCheckCacheEntry entry = rootTopicCheckCache.get(cacheKey);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.createdAt() > ROOT_TOPIC_CHECK_CACHE_TTL_MS) {
            rootTopicCheckCache.remove(cacheKey, entry);
            return null;
        }
        return entry.response();
    }

    private String buildRootTopicCheckCacheKey(Long roomId, Long parentId, String question) {
        String normalizedQuestion = defaultString(question)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        return roomId + "|" + parentId + "|" + normalizedQuestion;
    }

    @Transactional
    public ChatResponse ask(String authorization, Long roomId, Long parentId, String userMessage) {
        return ask(authorization, roomId, parentId, userMessage, false, false);
    }

    @Transactional
    public ChatResponse ask(String authorization, Long roomId, Long parentId, String userMessage, boolean forceCreateUnrelated) {
        return ask(authorization, roomId, parentId, userMessage, forceCreateUnrelated, false);
    }

    @Transactional
    public ChatResponse ask(String authorization, Long roomId, Long parentId, String userMessage, boolean forceCreateUnrelated, boolean skipRootTopicGuard) {
        // 1. 권한 및 대화방 검증
        validateAuthorization(authorization);

        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Chat room not found."));

        if (parentId != null && !forceCreateUnrelated && !skipRootTopicGuard) {
            RootTopicCheckResponse rootCheck = checkRootTopicRelation(
                    authorization,
                    roomId,
                    new RootTopicCheckRequest(parentId, userMessage)
            );
            if (rootCheck.isUnrelated()) {
                throw new IllegalArgumentException(
                        "ROOT_TOPIC_UNRELATED|" + defaultString(rootCheck.getRootTopic()) + "|" + rootCheck.getSimilarity()
                );
            }
        }

        // 2. 요청받은 부모 노드 (우선 프론트엔드가 준 임시 위치로 탐색)
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

        // 4. [초고속 처리] AI 답변만 즉시 생성! (사용자는 대기 시간 없이 답변을 받음)
        String aiAnswer = rabbitGuardService.chat(roomId, userMessage);

        // 5. AI 답변 임시 저장
        ChatMessage aiSaved = chatMessageRepository.save(ChatMessage.builder()
                .chatRoom(room)
                .parent(userSaved)
                .sender(SenderRole.AI)
                .content(aiAnswer)
                .depth(initialDepth)
                .build());

        // 6. 비동기 트리 후처리 트리거
        markTreeProcessingStarted(roomId);
        triggerTreePostProcessingAsync(
                authorization,
                roomId,
                parentId,
                userMessage,
                userSaved.getId(),
                aiSaved.getId(),
                forceCreateUnrelated, // 🌟 이제 여기서 벡터 유사도가 낮으면 비동기 내부 로직이 판단해 강제로 true 처럼 동작하게 고칠 겁니다!
                false
        );

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

    @Transactional(readOnly = true)
    public ChildNodeRecommendationResponse getDirectChildRecommendations(
            String authorization,
            Long roomId,
            Long nodeId
    ) {
        validateAuthorization(authorization);

        ChatMessage parentNode = findRequiredAiNodeInRoom(roomId, nodeId);
        String topic = normalizeRecommendedParentTopic(resolveInsightTitle(parentNode));
        String parentQuestion = "";
        ChatMessage userNode = parentNode.getParent();
        if (userNode != null && userNode.getSender() == SenderRole.USER) {
            parentQuestion = stripSystemPrefix(defaultString(userNode.getContent()));
        }

        List<String> recommendations = recommendDirectChildren(topic, parentQuestion, parentNode.getContent());
        return ChildNodeRecommendationResponse.builder()
                .nodeId(parentNode.getId())
                .recommendations(recommendations)
                .build();
    }

    @Transactional
    public ChatResponse createRecommendedDirectChild(
            String authorization,
            Long roomId,
            Long parentNodeId,
            String requestedSubtopic
    ) {
        validateAuthorization(authorization);

        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Chat room not found."));
        ChatMessage parentNode = findRequiredAiNodeInRoom(roomId, parentNodeId);

        String parentTopic = normalizeRecommendedParentTopic(resolveInsightTitle(parentNode));
        String subtopic = normalizeRecommendedSubtopic(requestedSubtopic, parentTopic);
        String autoQuestion = buildRecommendedChildQuestion(parentTopic, subtopic);
        int depth = parentNode.getDepth() + 1;
        String nodeTitle = buildRecommendedChildNodeTitle(parentTopic, subtopic);

        ChatMessage userSaved = chatMessageRepository.save(ChatMessage.builder()
                .chatRoom(room)
                .parent(parentNode)
                .sender(SenderRole.USER)
                .content(autoQuestion)
                .nodeTitle(nodeTitle)
                .depth(depth)
                .build());

        String aiAnswer = rabbitGuardService.chat(roomId, autoQuestion);

        ChatMessage aiSaved = chatMessageRepository.save(ChatMessage.builder()
                .chatRoom(room)
                .parent(userSaved)
                .sender(SenderRole.AI)
                .content(aiAnswer)
                .depth(depth)
                .build());

        return ChatResponse.builder()
                .answer(aiAnswer)
                .newNodeId(aiSaved.getId())
                .resolvedParentId(parentNode.getId())
                .nodeTitle(nodeTitle)
                .level1Topic("")
                .level2Topic("")
                .depth(depth)
                .build();
    }

    private ChatMessage findRequiredAiNodeInRoom(Long roomId, Long nodeId) {
        ChatMessage node = chatMessageRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found."));

        Long nodeRoomId = node.getChatRoom() != null ? node.getChatRoom().getId() : null;
        if (!Objects.equals(nodeRoomId, roomId)) {
            throw new IllegalArgumentException("Node does not belong to the room.");
        }
        if (node.getSender() != SenderRole.AI) {
            throw new IllegalArgumentException("Selected node must be an AI node.");
        }
        return node;
    }

    private List<String> recommendDirectChildren(String parentTopic, String parentQuestion, String parentAnswer) {
        String normalizedTopic = defaultString(parentTopic);
        String normalizedQuestion = defaultString(parentQuestion);
        String answerPreview = trimToLength(defaultString(parentAnswer).replaceAll("\\s+", " "), 220);

        String prompt = """
                [Task]
                아래 부모 노드의 바로 한 단계 하위 노드 주제를 최대 3개 추천하세요.
                각 항목은 짧은 단어구(명사구) 형태로 작성하세요.

                [Rules]
                1) 즉시 하위 주제로만 작성
                2) 문장형/설명형 금지
                3) 중복 금지
                4) 한국어 우선

                [Parent Topic]
                %s

                [Parent Question]
                %s

                [Parent Answer Summary]
                %s

                JSON only:
                {"children":["...","...","..."]}
                """.formatted(normalizedTopic, normalizedQuestion, answerPreview);

        try {
            String raw = conversationTreeAiService.recommendDirectChildren(prompt);
            List<String> parsed = parseDirectChildRecommendations(raw);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        } catch (Exception e) {
            log.debug("Direct-child recommendation generation failed for topic '{}': {}", normalizedTopic, e.getMessage());
        }

        return fallbackDirectChildRecommendations(normalizedTopic);
    }

    private List<String> parseDirectChildRecommendations(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        String json = extractFirstJsonObject(raw);
        if (!json.isBlank()) {
            try {
                JsonNode root = new ObjectMapper().readTree(json);
                JsonNode children = root.path("children");
                if (children.isArray()) {
                    for (JsonNode child : children) {
                        addDirectChildRecommendation(unique, child.asText(""));
                        if (unique.size() >= 3) {
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Direct-child recommendation JSON parse failed: {}", e.getMessage());
            }
        }

        if (unique.isEmpty()) {
            for (String token : RECOMMENDATION_LINE_SPLIT_PATTERN.split(raw)) {
                addDirectChildRecommendation(unique, token);
                if (unique.size() >= 3) {
                    break;
                }
            }
        }

        return unique.stream().limit(3).toList();
    }

    private void addDirectChildRecommendation(Set<String> unique, String candidate) {
        if (unique.size() >= 3) {
            return;
        }
        String normalized = normalizeDirectChildRecommendation(candidate);
        if (normalized.length() < 2) {
            return;
        }
        unique.add(normalized);
    }

    private String normalizeDirectChildRecommendation(String value) {
        String normalized = defaultString(value);
        normalized = RECOMMENDATION_PREFIX_PATTERN.matcher(normalized).replaceFirst("");
        normalized = normalized.replace("\"", "").replace("'", "");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        normalized = normalized.replaceAll("[.,!?;:]+$", "").trim();
        if (normalized.length() > 24) {
            normalized = trimToLength(normalized, 24);
        }
        return normalized;
    }

    private String extractFirstJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return raw.substring(start, end + 1).trim();
    }

    private List<String> fallbackDirectChildRecommendations(String topic) {
        String normalizedTopic = normalizeDirectChildRecommendation(topic);
        if (normalizedTopic.isBlank()) {
            return List.of("핵심 개념", "동작 원리", "활용 예시");
        }

        LinkedHashSet<String> fallback = new LinkedHashSet<>();
        addDirectChildRecommendation(fallback, normalizedTopic + " 핵심 개념");
        addDirectChildRecommendation(fallback, normalizedTopic + " 동작 원리");
        addDirectChildRecommendation(fallback, normalizedTopic + " 활용 예시");
        return fallback.stream().limit(3).toList();
    }

    private String normalizeRecommendedSubtopic(String requestedSubtopic, String fallbackTopic) {
        String normalized = normalizeDirectChildRecommendation(requestedSubtopic);
        if (!normalized.isBlank()) {
            return normalized;
        }

        String fallback = normalizeDirectChildRecommendation(fallbackTopic);
        if (!fallback.isBlank()) {
            return fallback;
        }
        return "하위 주제";
    }

    private String normalizeRecommendedParentTopic(String parentTopic) {
        String normalized = normalizeDirectChildRecommendation(parentTopic);
        normalized = stripTrailingParentContext(normalized);
        if (!normalized.isBlank()) {
            return normalized;
        }
        return "상위 노드";
    }

    private String stripTrailingParentContext(String text) {
        String normalized = defaultString(text);
        while (TRAILING_PARENT_CONTEXT_PATTERN.matcher(normalized).find()) {
            normalized = TRAILING_PARENT_CONTEXT_PATTERN.matcher(normalized).replaceFirst("").trim();
        }
        return normalized;
    }

    private String buildRecommendedChildQuestion(String parentTopic, String subtopic) {
        return parentTopic + "과 관련하여, " + subtopic + "에 대해 알려줘";
    }

    private String buildRecommendedChildNodeTitle(String parentTopic, String subtopic) {
        String composed = subtopic + " (" + parentTopic + ")";
        return trimToLength(composed, 110);
    }

    private void triggerTreePostProcessingAsync(
            String authorization,
            Long roomId,
            Long requestedParentId,
            String userMessage,
            Long userMessageId,
            Long aiMessageId,
            boolean forceCreateUnrelated,
            boolean checkRootTopicInBackground
    ) {
        Runnable postProcessTask = () -> CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    try {
                        boolean keepRequestedParent = forceCreateUnrelated;
                        if (!keepRequestedParent && checkRootTopicInBackground && requestedParentId != null) {
                            RootTopicCheckResponse rootCheck = checkRootTopicRelation(
                                    authorization,
                                    roomId,
                                    new RootTopicCheckRequest(requestedParentId, userMessage)
                            );
                            keepRequestedParent = rootCheck.isUnrelated();
                        }
                        applyTreePostProcessing(roomId, requestedParentId, userMessage, userMessageId, aiMessageId, keepRequestedParent);
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



    // 🌟 밸런스 패치 완료된 예선전 채점 로직
    // 🛡️ [컴파일 에러 완치 방어선] 레거시 5인자 호출을 무력화하는 오버로딩
    // =====================================================================
    // 🛡️ [메인 찐 엔진] 4개 인수를 처리하는 핵심 채점 로직
    // =====================================================================
    // 🌟 밸런스 패치 완료된 예선전 채점 로직 (납치 완벽 방어 버전)
    private SubtopicAnchor rankSubtopicAnchors(
            List<SubtopicAnchor> anchors,
            List<ChatMessage> history,
            String userMessage,
            SubtopicAnchor currentActiveAnchor
    ) {
        if (anchors == null || anchors.isEmpty()) {
            return null;
        }

        String normalizedMessage = normalizeForMatch(userMessage);
        if (normalizedMessage.isBlank()) {
            normalizedMessage = userMessage.toLowerCase(Locale.ROOT).trim();
        }

        // GPT 심판장에게 도메인 분류 요청
        SubtopicAnchor aiSelectedAnchor = selectAnchorByAi(anchors, history, userMessage, currentActiveAnchor);

        dev.langchain4j.data.embedding.Embedding queryEmbedding = contextSimilarityService.embedQuery(userMessage);

        double bestScore = Double.NEGATIVE_INFINITY;
        SubtopicAnchor bestAnchor = null;

        for (SubtopicAnchor anchor : anchors) {
            boolean isDirectTopicMatch = containsTopic(normalizedMessage, anchor.topic());
            double hintScore = contextSimilarityService.hintOverlapScore(userMessage, branchDescriptor(anchor));

            double similarityScore = contextSimilarityService.score(
                    queryEmbedding,
                    branchDescriptor(anchor),
                    buildAnchorProfileSamples(anchor, history),
                    userMessage
            );
            double centroidScore = centroidAnchorScore(anchor, history, userMessage);

            boolean isAiSelected = aiSelectedAnchor != null && anchor.aiNode().getId().equals(aiSelectedAnchor.aiNode().getId());

            // 🚨 [범인 검거 패치] AI가 "연관 없음(0번)" 판정을 내렸다면, 단순히 마지막에 봤던 방이라는 이유로 점수(30점)를 퍼주지 않습니다!
            double focusWeight = 0.0;
            if (currentActiveAnchor != null && anchor.aiNode().getId().equals(currentActiveAnchor.aiNode().getId())) {
                // AI가 최소한의 연관성을 인정했을 때만 30점을 주고, 아니면 가중치를 대폭 축소(5점)합니다.
                focusWeight = (aiSelectedAnchor != null) ? 30.0 : 5.0;
            }

            double totalScore = (isDirectTopicMatch ? 20.0 : 0.0)
                    + (hintScore * 5.0)
                    + (similarityScore * 10.0)
                    + (isAiSelected ? 200.0 : 0.0)
                    + focusWeight;

            log.info("Routing candidate topic='{}' similarity={} aiSelected={} total={}",
                    anchor.topic(), round(similarityScore), isAiSelected, round(totalScore));

            if (totalScore > bestScore) {
                bestScore = totalScore;
                bestAnchor = anchor;
            }
        }

        // 🚨 [철벽 방어선 추가] AI의 지지(aiSelectedAnchor)가 없다면, 커트라인을 15점에서 35점으로 대폭 상향하여 강제 납치를 원천 봉쇄합니다.
        double cutoff = (aiSelectedAnchor != null) ? 15.0 : 35.0;

        if (bestAnchor != null && bestScore < cutoff) {
            log.info("ℹ️ 최고 매칭 기둥('{}')의 총점({})이 커트라인({}) 미달입니다. 독립 기둥을 개척합니다.", bestAnchor.topic(), round(bestScore), cutoff);
            return null; // 완벽하게 튕겨내서 새 방(Depth 1)을 파러 감
        }

        return bestAnchor;
    }

    // 🛡️ [오버로딩 1] 레거시 코드 중 인자를 3개만 던지는 구역 방어
    private SubtopicAnchor rankSubtopicAnchors(
            List<SubtopicAnchor> anchors,
            List<ChatMessage> history,
            String userMessage
    ) {
        return rankSubtopicAnchors(anchors, history, userMessage, null);
    }

    // 🛡️ [오버로딩 2] 레거시 코드 중 인자를 5개나 던지는 구역 방어 (644, 2520, 2627번 라인 에러 완치)
    private SubtopicAnchor rankSubtopicAnchors(
            List<SubtopicAnchor> anchors,
            List<ChatMessage> history,
            String userMessage,
            SubtopicAnchor currentActiveAnchor,
            Object extraArgument // 5번째 남는 인자를 흡수해서 버림
    ) {
        return rankSubtopicAnchors(anchors, history, userMessage, currentActiveAnchor);
    }



    // 🚨 기존에 있던 resolveParentNodeForIntent를 이 코드로 덮어써서 파서 결과를 인계받게 합니다!
    // 🌟 2. 라우팅 심장 복구: 앵커(기둥)들 먼저 긁어와서 유사도 심사대로 보냄
    private ChatMessage resolveParentNodeForIntent(List<ChatMessage> history, String userMessage, TopicExtractionResponse extracted) {
        ChatMessage rootNode = history.stream().filter(m -> m.getDepth() == 0 && m.getSender() == SenderRole.AI).findFirst().orElse(null);
        List<SubtopicAnchor> anchors = findSubtopicAnchors(history);

        // 후보 기둥들(운영체제, DB 등)을 대상으로 벡터 유사도 랭킹 매기기
        SubtopicAnchor bestAnchor = rankSubtopicAnchors(anchors, history, userMessage, null, extracted);

        // 🚨 너무 안 맞아서 튕겨 나갔으면 -> 루트 밑에 새로운 기둥(Depth 1)으로 보냄!
        if (bestAnchor == null) {
            log.info("🚀 [Ontology Guard] 기존 기둥들과 유사도 미달. 새로운 기둥으로 독립합니다.");
            return rootNode;
        }

        // 합격한 기둥 안에서 가장 적합한 부모를 찾음
        return chooseParentWithinAnchor(bestAnchor, null, history, userMessage);
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



    /**
     * 상호 호환성을 위한 간단한 매칭 도우미
     */
    private boolean isDirectTopicMatch(String normalizedMessage, String anchorTopic) {
        return normalizedMessage.contains(anchorTopic) || anchorTopic.contains(normalizedMessage);
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

    // 🌟 [교정 완료] GPT 결승전 부모 선택 오케스트레이터 (시퀀스 버그 완치 버전)
    private ChatMessage resolveBestParentWithLLM(String userMessage, List<ChatMessage> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        try {
            String pureTargetQuestion = userMessage.replaceAll("\\(AI 답변 힌트:.*\\)", "").trim();
            StringBuilder options = new StringBuilder("[\n");
            int maxDepth = -1;
            String deepestTopic = "";

            for (int i = 0; i < candidates.size(); i++) {
                ChatMessage c = candidates.get(i);
                String cleanTitle = compactNodeTitle(c);
                cleanTitle = cleanTitle.replaceAll("\\[AUTO_SUBTOPIC(?:_ACK)?\\]", "").trim();
                cleanTitle = cleanTitle.replaceAll("[\n\r\"'\\\\]", " ");

                String userRealQuestion = "알 수 없음";
                ChatMessage userNode = c.getParent();
                if (userNode != null && userNode.getSender() == SenderRole.USER) {
                    userRealQuestion = stripSystemPrefix(defaultString(userNode.getContent()));
                    userRealQuestion = userRealQuestion.replaceAll("[\n\r\"'\\\\]", " ").trim();
                }

                if (c.getDepth() > maxDepth) {
                    maxDepth = c.getDepth();
                    deepestTopic = userRealQuestion.equals("알 수 없음") ? cleanTitle : userRealQuestion;
                }

                options.append(String.format("  {\"id\": %d, \"depth\": %d, \"topic\": \"%s\", \"user_question\": \"%s\"}",
                        c.getId(), c.getDepth(), trimToLength(cleanTitle, 30), trimToLength(userRealQuestion, 50)));
                if (i < candidates.size() - 1) options.append(",\n");
            }
            options.append("\n]");

            // 🎯 [자료구조 수용 및 시퀀스 방어 규칙 확립] 지식 엔진 프롬프트
            String prompt = String.format("""
        <System_Persona>
        당신은 세상의 모든 지식을 논리적 계층 구조로 엮어내는 '수석 지식 그래프 아키텍트'입니다.
        사용자의 질문(Target)을 후보군(Candidates Dataset) 중 가장 논리적인 '직계 부모(Immediate Parent)'에 연결하십시오.
        </System_Persona>
        
        <Ontology_Rules>
        아래 [판단 프로세스]를 1번부터 순서대로 '엄격하게' 적용하여 평가하십시오.
        
        [Step 1. Sequence & Sibling 검사 (🚨최우선 절대 방어선🚨)]
        - Target과 Candidate가 동일한 부모를 공유해야 하는 '순서, 단계, 버전, 레벨' 관계인가? (예: 1정규화와 2정규화, 1단계와 2단계, OSI 3계층과 4계층)
        - 만약 그렇다면, 이들은 수평적 형제(Sibling)입니다! 논리적 선행 조건이 있더라도 절대 부모-자식으로 묶으면 안 됩니다.
        - 판결: logic_rel을 "Sibling"으로 적고, score는 무조건 "0"점을 부여한 뒤 Step 2를 무시하고 평가를 종료하십시오.
        
        [Step 2. Hyponym & Function & Core Subject 검사]
        - Step 1에 해당하지 않는 경우에만 평가합니다.
        - Target이 Candidate의 '구체적인 종류(하위 사례)', '구성 요소', Candidate가 수행하는 '핵심 기능/동작 원리(Function)', 또는 🚨'핵심 연구 대상/도구(예: 알고리즘과 그 대상이 되는 자료구조)'🚨에 해당하는가?
        - (예: '그래프', '스택', '큐' 등의 자료구조는 '알고리즘'의 핵심 연구 도구이자 대상이므로 자식으로 인정합니다.)
        - 판결: 그렇다면 logic_rel을 "Hyponym"으로 적고, score를 "90~100"점 사이로 부여하십시오.
        
        [Step 3. 그 외]
        - 주어가 생략된 꼬리 질문이면 가장 깊은 Depth의 노드 '%s'의 자식으로 간주합니다.
        - 그 외 단순 연관이나 대등한 개념이면 score를 0~10점으로 부여하십시오.
        </Ontology_Rules>
        
        <Input_Context>
        - Target Concept: "%s"
        - Candidates Dataset:
        %s
        </Input_Context>
        
        <Output_Constraint>
        JSON으로만 응답하십시오. (No Markdown)
        {
          "target_analysis": "Target 질문의 핵심 의도 요약",
          "evaluations": [ { "id": ID, "logic_rel": "Hyponym | Hypernym | Sibling | Irrelevant", "score": 점수, "reason": "어떤 Step의 룰을 적용했는지 근거 요약" } ]
        }
        </Output_Constraint>
        """, deepestTopic, pureTargetQuestion, options.toString());

            String rawAnswer = conversationTreeAiService.selectBestSubtopic(prompt);
            int start = rawAnswer.indexOf("{");
            int end = rawAnswer.lastIndexOf("}");
            if (start == -1 || end == -1) return candidates.get(0);

            JsonNode rootNode = new ObjectMapper().readTree(rawAnswer.substring(start, end + 1));
            log.info("🧠 [Taxonomy Insight] {}", rootNode.path("target_analysis").asText());

            JsonNode evaluations = rootNode.path("evaluations");
            Long bestId = null;
            int highestAdjustedScore = -1;
            int winningOriginalScore = 0;

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

                int adjustedScore = score;

                // 🎯 [소주제 가산점 관성 반영 구역]
                if (score >= 65) {
                    adjustedScore = score + (currentDepth * 10);
                    if (currentDepth >= 2) {
                        adjustedScore += 20;
                    }
                }

                log.info(" 🔍 Node [{}] | Logic: {} | Original Score: {} | Adjusted Score: {} | Depth: {}",
                        id, eval.path("logic_rel").asText(), score, adjustedScore, currentDepth);

                if (adjustedScore > highestAdjustedScore) {
                    highestAdjustedScore = adjustedScore;
                    bestId = id;
                    winningOriginalScore = score;
                }
            }

            if (bestId != null) {
                Long finalBestId = bestId;
                ChatMessage selectedParent = candidates.stream()
                        .filter(c -> c.getId().equals(finalBestId))
                        .findFirst()
                        .orElse(candidates.get(0));

                // 🌟 [방어선 1] 정규식 기반 시리즈 계열 감지 인터셉터
                String parentTitle = selectedParent.getNodeTitle() != null ? selectedParent.getNodeTitle() : "";
                String baseParent = parentTitle.replaceAll("[0-9제차단계층세대vV\\s]", "");
                String baseTarget = pureTargetQuestion.replaceAll("[0-9제차단계층세대vV\\s]", "");

                if (parentTitle.matches(".*\\d.*") && pureTargetQuestion.matches(".*\\d.*")
                        && !baseParent.isEmpty() && baseParent.equals(baseTarget)) {
                    log.warn("🚨 [Interceptor] 범용 시리즈 계열('{}' vs '{}') 감지! 자바 로직으로 강제 수평(Sibling) 처리합니다.", parentTitle, pureTargetQuestion);
                    return null;
                }

                // ⚡ [방어선 2] 네트워크 프로토콜 강제 흡수 가비지 컬렉터
                // 점수가 0점이어도 단어 필터링에 걸리면 조기 리턴 전에 여기서 먼저 낚아챕니다.
                String lowerTarget = pureTargetQuestion.toLowerCase();
                if (lowerTarget.contains("http") || lowerTarget.contains("tcp") || lowerTarget.contains("udp") || lowerTarget.contains("protocol")) {
                    java.util.Optional<ChatMessage> networkParent = candidates.stream()
                            .filter(c -> c.getDepth() >= 2 && c.getNodeTitle() != null && c.getNodeTitle().contains("네트워크"))
                            .findFirst();

                    if (networkParent.isPresent()) {
                        log.warn("⚡ [Interceptor] 네트워크 통신 핵심 키워드 감지! 기존 '{}' 방 하위로 강제 주입합니다.", networkParent.get().getNodeTitle());
                        return networkParent.get();
                    }
                }

                // 🚨 [커트라인 가드레일 - 위치 조정 완료 ⚠️]
                // 인터셉터에 걸리지 않은 '진짜 0점짜리 무관계 노드'들만 최종적으로 여기서 걸러져 수평 배치(null)됩니다.
                if (winningOriginalScore < 65) {
                    log.info("ℹ️ 최적 후보의 순수 점수({})가 부모 합격 점수(65점) 미달이며 인터셉터를 통과하지 못했습니다. 수평 형제 배치(null 반환)를 수행합니다.", winningOriginalScore);
                    return null;
                }

                return selectedParent;
            }
        } catch (Exception e) {
            log.error("Engineering Pipeline Error: {}", e.getMessage());
        }
        return candidates.get(0);
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
    // 🌟 [범용 AI 기둥 선택기] 학문 과목 도메인 분리구 설계
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
                당신은 세상의 모든 학문과 지식 체계를 완벽하게 이해하고 분류하는 '수석 지식 그래프 아키텍트'입니다. 
                사용자의 질문 문맥을 분석하여, 아래 [카테고리 후보] 중 가장 같은 '지식 도메인'이나 '상위 카테고리'에 속하는 기둥을 선택하세요.
                
                [Context]
                현재 활성화된 대주제: %s
                
                [User Question & AI Hint]
                "%s"
                
                [카테고리 후보 목록]
                %s
                
                    [Reasoning Steps]
               1. 도메인 매칭: 사용자의 질문이 어떤 넓은 범주에 속하는지 파악하세요.
                  - 🚨 [근본 학문 분류의 원칙]: 질문의 개념이 단순히 '활용'되는 1차원적인 응용 분야(예: 프로그래밍, 일상생활, 도구)로 뭉뚱그려 분류하지 마십시오. 반드시 해당 개념이 탄생하고 이론적으로 깊게 다루어지는 **'가장 근본적인 학문/전공 기초 도메인'**을 찾아 매칭해야 합니다.\s
                  - (예를 들어, 수술용 메스를 '물건 자르기'가 아니라 '의학/외과학'으로 분류하듯, 지식의 뼈대가 되는 학문을 찾으세요.)
                  - 🚨 아무리 비슷한 분야라도, 세부 카테고리나 다루는 대상의 본질이 다르면 서로 다른 기둥으로 분류해야 합니다.
               2. 최종 선택: 파악한 근본 도메인과 일치하는 후보 기둥 번호를 선택하세요. 만약 후보 목록에 있는 어떤 기둥과도 학문 자체가 완전히 다른 새로운 지식 카테고리라면 무조건 0을 입력하세요.
                
                [Output Format]
                반드시 아래 JSON 형식으로만 응답하세요. 마크다운(```json) 포함 금지.
                {
                  "reasoning": "분류 과정을 1줄로 요약",
                  "selectedIndex": 정답숫자(매칭되는 카테고리가 전혀 없다면 오직 0)
                }
                """, currentContext, userMessage, options.toString());

            String rawAnswer = conversationTreeAiService.selectBestSubtopic(prompt);

            int start = rawAnswer.indexOf("{");
            int end = rawAnswer.lastIndexOf("}");
            if (start != -1 && end != -1) {
                String jsonStr = rawAnswer.substring(start, end + 1);
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> result = mapper.readValue(jsonStr, new TypeReference<Map<String, Object>>(){});

                int selectedIndex = Integer.parseInt(result.get("selectedIndex").toString());
                int chosenIdx = selectedIndex - 1;
                String reasoning = result.get("reasoning").toString();

                log.info("🎯 GPT 예선전(기둥) 판결: {}번 후보 선택 (이유: {})", selectedIndex, reasoning);

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

    private List<String> buildRootTopicCheckContext(List<ChatMessage> history, ChatMessage rootUser) {
        List<String> context = new ArrayList<>();
        addIfNotBlank(context, rootUser.getLevel1Topic());
        addIfNotBlank(context, rootUser.getNodeTitle());
        addIfNotBlank(context, stripSystemPrefix(defaultString(rootUser.getContent())));

        history.stream()
                .filter(message -> message.getSender() == SenderRole.USER)
                .filter(message -> message.getDepth() <= 1)
                .limit(12)
                .forEach(message -> {
                    addIfNotBlank(context, message.getLevel1Topic());
                    addIfNotBlank(context, message.getLevel2Topic());
                    addIfNotBlank(context, message.getNodeTitle());
                    addIfNotBlank(context, stripSystemPrefix(defaultString(message.getContent())));
                });
        return context;
    }

    private List<String> buildParentPathContext(Long parentId) {
        if (parentId == null) {
            return List.of();
        }

        List<String> context = new ArrayList<>();
        ChatMessage cursor = chatMessageRepository.findById(parentId).orElse(null);
        int guard = 0;
        while (cursor != null && guard++ < 12) {
            addIfNotBlank(context, cursor.getNodeTitle());
            addIfNotBlank(context, stripSystemPrefix(defaultString(cursor.getContent())));
            ChatMessage userNode = cursor.getSender() == SenderRole.AI ? cursor.getParent() : cursor;
            if (userNode != null) {
                addIfNotBlank(context, userNode.getNodeTitle());
                addIfNotBlank(context, userNode.getLevel1Topic());
                addIfNotBlank(context, userNode.getLevel2Topic());
                addIfNotBlank(context, stripSystemPrefix(defaultString(userNode.getContent())));
            }
            cursor = userNode != null ? userNode.getParent() : null;
        }
        return context;
    }

    private double topicRelationshipScore(String question, List<String> contextSamples) {
        if (question == null || question.isBlank() || contextSamples == null || contextSamples.isEmpty()) {
            return 0.0;
        }

        double semanticScore = contextSimilarityService.relationshipScore(question, contextSamples);
        double lexicalScore = tokenOverlapRatio(question, String.join(" ", contextSamples));
        return Math.max(semanticScore, lexicalScore);
    }

    private boolean classifyRootTopicUnrelated(String question, String rootTopic, List<String> rootContext) {
        try {
            String context = rootContext == null ? "" : String.join(" / ", rootContext);
            String prompt = """
                    Decide whether the user question belongs to the same broad root topic.
                    Return exactly one candidate from this list: RELATED, UNRELATED.

                    Root topic: %s
                    Root context: %s
                    User question: %s

                    RELATED means the question can reasonably fit inside the root topic.
                    UNRELATED means it should start a different chat room.
                    Candidates:
                    - RELATED
                    - UNRELATED
                    """.formatted(
                    trimToLength(defaultString(rootTopic), 80),
                    trimToLength(defaultString(context), 600),
                    trimToLength(defaultString(question), 240)
            );
            String result = defaultString(conversationTreeAiService.selectBestSubtopic(prompt));
            return "UNRELATED".equalsIgnoreCase(result);
        } catch (Exception e) {
            return false;
        }
    }

    private double tokenOverlapRatio(String left, String right) {
        Set<String> leftTokens = extractCheckTokens(left);
        Set<String> rightTokens = extractCheckTokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }

        int overlap = 0;
        for (String token : leftTokens) {
            if (rightTokens.contains(token)) {
                overlap++;
            }
        }
        return (double) overlap / Math.min(leftTokens.size(), rightTokens.size());
    }

    private Set<String> extractCheckTokens(String text) {
        String normalized = defaultString(text).toLowerCase(Locale.ROOT)
                .replaceAll("[^0-9a-z가-힣]+", " ");
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            String trimmed = token.trim();
            if (trimmed.length() < 2 || ROOT_TOPIC_CHECK_STOP_WORDS.contains(trimmed)) {
                continue;
            }
            tokens.add(trimmed);
        }
        return tokens;
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

    // =====================================================================
    // 🚀 [메인 파이프라인] 민교님의 의도 기반 조건부 기둥 제어 엔진
    // =====================================================================
    private void applyTreePostProcessing(
            Long roomId,
            Long requestedParentId,
            String userMessage,
            Long userMessageId,
            Long aiMessageId,
            boolean keepRequestedParent
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

        // 📦 1. [AI 답변 힌트 활용] 날것의 유저 질문에 AI가 말한 핵심 지식을 풀칠해 풍성한 문맥 조립
        String aiAnswer = aiSaved.getContent();
        String aiHint = aiAnswer.substring(0, Math.min(aiAnswer.length(), 100)).replace("\n", " ");
        String contextForRouting = userMessage + " (AI 답변 힌트: " + aiHint + ")";

        // 현재 방에 저장된 대과목명(level1Topic) 기본값 확보
        String level1Topic = historyBeforeCurrent.stream()
                .filter(m -> m.getSender() == SenderRole.USER && m.getDepth() == 0)
                .map(ChatMessage::getLevel1Topic).filter(Objects::nonNull).findFirst()
                .orElse("컴퓨터 공학");

        List<SubtopicAnchor> anchors = findSubtopicAnchors(historyBeforeCurrent);
        ChatMessage lastAiNode = historyBeforeCurrent.stream()
                .filter(m -> m.getSender() == SenderRole.AI)
                .max(Comparator.comparing(ChatMessage::getCreatedAt))
                .orElse(null);
        SubtopicAnchor currentAnchor = findAnchorForAiNode(lastAiNode, anchors);

        // 📦 2. [현재 소주제 목록인 운영체제, 데이터베이스 등과 비교]
        SubtopicAnchor bestAnchor = rankSubtopicAnchors(anchors, historyBeforeCurrent, contextForRouting, currentAnchor);

        ChatMessage parentNode = null;
        int currentDepth = 0;
        String level2Topic = "";

        if (keepRequestedParent && requestedParent != null) {
            parentNode = requestedParent;
            currentDepth = parentNode.getDepth() + 1;
            level2Topic = parentNode.getLevel2Topic() != null ? parentNode.getLevel2Topic() : "세부 학습";
        }
        // 🎯 3. [이게 맞다고 싶으면 그 소주제 밑으로 들어가면 되고]
        else if (bestAnchor != null) {
            log.info("🎯 [Ontology Router] 기존 기둥 매칭 성공 -> '{}' 기둥 하위로 진입합니다.", bestAnchor.topic());
            parentNode = chooseParentWithinAnchor(bestAnchor, lastAiNode, historyBeforeCurrent, contextForRouting);
            currentDepth = parentNode.getDepth() + 1;
            level2Topic = bestAnchor.topic(); // 기존 방의 기둥명 상속
        }
        // 🚨 4. [아니다 싶으면은 그제서야 이 노드의 기둥 이름을 지어서 저장하면 돼]
        else {
            log.info("🚀 [Ontology Router] 일치하는 기둥 없음 -> 순서에 따라 '그제서야' 거시적 새 기둥(Depth 1)을 개척합니다.");

            // 최상위 루트 노드를 부모로 지정하여 독립 기둥으로 설정
            parentNode = historyBeforeCurrent.stream()
                    .filter(m -> m.getDepth() == 0 && m.getSender() == SenderRole.AI)
                    .findFirst()
                    .orElse(lastAiNode);
            currentDepth = 1;

            // 너무 좁은 기둥명('정렬')이 아닌, 거시적 과목 단원명('알고리즘', '자료구조')을 유추해서 기둥 이름 채택
            String refinePrompt = "사용자의 질문과 AI 답변 힌트를 종합 분석하여, 이 대화 줄기가 속할 지식의 '거시적인 대분류 또는 카테고리명'(예: 세계사, 거시경제학, 유전학, 근력운동, 프로그래밍, 서양철학 등)을 딱 한 단어의 명사형으로 추출하세요. 부가설명 없이 오직 단어만 출력하세요.\n"
                    + "문맥: " + contextForRouting + "\n추천 대분류명: ";
            try {
                String refinedTopic = conversationTreePlannerService.aiLabel(refinePrompt, userMessage, 15);
                if (refinedTopic != null && !refinedTopic.isBlank() && !refinedTopic.contains("일반")) {
                    level2Topic = refinedTopic.trim();
                } else {
                    level2Topic = "알고리즘";
                }
            } catch (Exception e) {
                level2Topic = "알고리즘";
            }
        }

        // 📊 5. [컴파일 에러 완치] 플래너 서비스의 (String, String, int, String) 정식 규격에 맞춰 빌드 요청
        ConversationTreePlannerService.TreePlan treePlan = conversationTreePlannerService.planNode(
                level1Topic,
                level2Topic,
                currentDepth,
                userMessage
        );

        // 최상위 루트 노드가 꼬이지 않도록 타이틀 가드 적용
        String finalNodeTitle = (currentDepth == 0) ? level1Topic : treePlan.nodeTitle();

        // 노드 영속성 마감 및 업데이트
        userSaved.updateTreePlacement(parentNode, currentDepth);
        userSaved.updateTreeMetadata(finalNodeTitle, level1Topic, level2Topic);
        ensureNodeTopicHints(userSaved, historyBeforeCurrent);
        aiSaved.updateDepth(currentDepth);
        aiSaved.updateTreeMetadata(finalNodeTitle, level1Topic, level2Topic);

        if (currentDepth == 0) {
            createInitialLevelTwoSeedNodes(room, aiSaved, level1Topic, userMessage);
        }

        log.info("🏁 [Framework] 최종 마인드맵 배치 완료 -> 부모 ID: {}, Depth: {}, 소주제 기둥명: '{}', 라벨 제목: '{}'",
                parentNode != null ? parentNode.getId() : "null", currentDepth, level2Topic, finalNodeTitle);
    }

    // =====================================================================
    // 🌟 안전장치: 이미 2단계 방으로 들어왔으면 절대 밖으로 튕기지 않게 방어
    // =====================================================================
    private ChatMessage chooseParentWithinAnchor(
            SubtopicAnchor anchor, ChatMessage requestedParent, List<ChatMessage> history, String userMessage) {

        String pureUserMessage = userMessage.replaceAll("\\(AI 답변 힌트:.*\\)", "").trim();

        if (isSeriesSiblingRequest(requestedParent, pureUserMessage) || hasExplicitSiblingIntent(pureUserMessage)) {
            return resolveSiblingParent(requestedParent, anchor.aiNode());
        }

        ChatMessage anchorBestParent = selectRelevantParentWithinAnchor(anchor, history, userMessage);

        if (anchorBestParent != null) {
            log.info("🤖 AI 라우팅 100% 신뢰 적용! 기둥 내 찐 부모: {}", anchorBestParent.getId());
            return anchorBestParent;
        }

        // 🚨 [필수 백업] 이미 2단계 유사도 검사를 통과해 방에 들어온 이상, 절대 밖으로 튕겨내지 않습니다!
        // 가장 최근에 대화한 해당 방의 노드 뒤에 무조건 엮어줍니다.
        log.info("🛡️ 세부 부모를 못 찾았으나, 강제로 현재 2단계 기둥({})에 연결합니다.", anchor.topic());
        ChatMessage lastNodeInPillar = history.stream()
                .filter(m -> m.getSender() == SenderRole.AI && m.getLevel2Topic() != null && m.getLevel2Topic().equals(anchor.topic()))
                .max(Comparator.comparing(ChatMessage::getCreatedAt))
                .orElse(anchor.aiNode());

        return requestedParent != null ? requestedParent : lastNodeInPillar;
    }

    // 🌟 똑똑하게 꼬리질문인지 확인해 주는 GPT 심사위원
    // 💡 레거시 숏컷용 메서드는 혹시 모를 내부 호출 충돌을 방지하기 위해 순정 복구 처리합니다.
    private ChatMessage resolveParentNodeForIntent(List<ChatMessage> history, String userMessage) {
        if (history == null || history.isEmpty()) return null;
        List<SubtopicAnchor> anchors = findSubtopicAnchors(history);
        ChatMessage lastAiNode = history.stream().filter(m -> m.getSender() == SenderRole.AI).max(Comparator.comparing(ChatMessage::getCreatedAt)).orElse(null);
        SubtopicAnchor currentAnchor = findAnchorForAiNode(lastAiNode, anchors);
        SubtopicAnchor bestAnchor = rankSubtopicAnchors(anchors, history, userMessage, currentAnchor);
        if (bestAnchor == null) return lastAiNode;
        return chooseParentWithinAnchor(bestAnchor, lastAiNode, history, userMessage);
    }


    // =====================================================================
    // 🛠️ 트리 노드 관리 (삭제 및 이동) 로직
    // =====================================================================

    /**
     * 🗑️ 노드 및 하위 트리 전체 삭제 (유저 질문 + AI 답변 세트 삭제)
     */
    @Transactional
    public void deleteNodeAndSubtree(String authorization, Long roomId, Long nodeId) {
        validateAuthorization(authorization);

        // 1. 프론트에서 넘어온 ID는 'AI 답변 노드'입니다.
        ChatMessage targetAiNode = chatMessageRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 노드입니다."));

        // 2. 짝꿍인 '유저 질문 노드'를 찾습니다.
        ChatMessage targetUserNode = targetAiNode.getParent();

        // 3. 내 밑에 달린 하위 트리(자식들)를 바닥부터 싹 다 지웁니다.
        deleteChildrenRecursively(targetAiNode.getId());

        // 4. AI 답변 노드를 먼저 삭제합니다.
        chatMessageRepository.delete(targetAiNode);

        // 🌟 5. 남겨진 짝꿍(유저 질문 노드)도 깔끔하게 삭제합니다.
        if (targetUserNode != null && targetUserNode.getSender() == SenderRole.USER) {
            chatMessageRepository.delete(targetUserNode);
        }

        log.info("🗑️ 노드 및 하위 트리 삭제 완료 (세트 삭제): {}", nodeId);
    }

    private void deleteChildrenRecursively(Long parentId) {
        List<ChatMessage> children = chatMessageRepository.findByParentId(parentId);
        for (ChatMessage child : children) {
            deleteChildrenRecursively(child.getId()); // 바닥 끝까지 파고들기
            chatMessageRepository.delete(child); // 밑에서부터 위로 차례대로 삭제
        }
    }

    /**
     * 🚚 노드 이동 (유저 질문 노드를 통째로 새 부모 밑으로 이사)
     */
    @Transactional
    public void moveNode(String authorization, Long roomId, Long nodeId, Long newParentId) {
        validateAuthorization(authorization);

        if (nodeId.equals(newParentId)) {
            throw new IllegalArgumentException("자기 자신 밑으로 이동할 수 없습니다.");
        }

        // 1. 프론트에서 넘어온 ID는 'AI 노드'입니다.
        ChatMessage sourceAiNode = chatMessageRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("이동할 노드를 찾을 수 없습니다."));
        ChatMessage targetAiNode = chatMessageRepository.findById(newParentId)
                .orElseThrow(() -> new IllegalArgumentException("새로운 부모 노드를 찾을 수 없습니다."));

        // 🌟 2. 실제로 새 집에 들어가야 할 녀석은 AI 노드가 아니라 짝꿍인 '유저 질문 노드'입니다!
        ChatMessage sourceUserNode = sourceAiNode.getParent();
        if (sourceUserNode == null || sourceUserNode.getSender() != SenderRole.USER) {
            throw new IllegalArgumentException("유저 질문 노드를 찾을 수 없습니다.");
        }

        // 🚨 순환 참조 방지 (내 자식 밑으로 들어가려 하면 예외 발생)
        if (isDescendant(nodeId, newParentId)) {
            throw new IllegalArgumentException("자신의 하위 노드로는 이동할 수 없습니다.");
        }

        // 3. 뎁스 차이 계산 (이사 갈 집의 뎁스 + 1 - 유저 노드의 현재 뎁스)
        int nextDepth = targetAiNode.getDepth() + 1;
        int depthDiff = nextDepth - sourceUserNode.getDepth();

        // 🌟 4. 유저 노드의 부모를 '새 부모(targetAiNode)'로 바꿉니다.
        sourceUserNode.updateTreePlacement(targetAiNode, nextDepth);
        chatMessageRepository.save(sourceUserNode); // DB 강제 저장(UPDATE)

        // 5. 내 짝꿍(AI)과 밑에 딸려가는 모든 자손들의 뎁스도 일괄 업데이트합니다.
        if (depthDiff != 0) {
            // 유저 노드의 자식들(본인 AI 노드 포함)부터 뎁스를 맞춰줍니다.
            updateChildrenDepthRecursively(sourceUserNode.getId(), depthDiff);
        }

        log.info("🚚 노드 이동 완료: {} (User:{}) -> 새 부모: {}", nodeId, sourceUserNode.getId(), newParentId);
    }

    @Transactional
    public void forceNodePlacement(String authorization, Long roomId, Long nodeId, Long parentId, String nodeTitle) {
        validateAuthorization(authorization);

        ChatMessage sourceAiNode = chatMessageRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("?몃뱶瑜?李얠쓣 ???놁뒿?덈떎."));
        ChatMessage sourceUserNode = sourceAiNode.getParent();
        if (sourceUserNode == null || sourceUserNode.getSender() != SenderRole.USER) {
            throw new IllegalArgumentException("?좎? 吏덈Ц ?몃뱶瑜?李얠쓣 ???놁뒿?덈떎.");
        }

        Long sourceRoomId = sourceAiNode.getChatRoom() != null ? sourceAiNode.getChatRoom().getId() : null;
        if (!Objects.equals(sourceRoomId, roomId)) {
            throw new IllegalArgumentException("?몃뱶媛 ?대떦 ??붾갑???띠븯吏 ?딆뒿?덈떎.");
        }

        ChatMessage targetAiNode = null;
        if (parentId != null) {
            if (Objects.equals(nodeId, parentId) || isDescendant(nodeId, parentId)) {
                throw new IllegalArgumentException("?먯떊???섏쐞 ?몃뱶濡쒕뒗 ?대룞?????놁뒿?덈떎.");
            }
            targetAiNode = chatMessageRepository.findById(parentId)
                    .orElseThrow(() -> new IllegalArgumentException("遺紐??몃뱶瑜?李얠쓣 ???놁뒿?덈떎."));
            Long targetRoomId = targetAiNode.getChatRoom() != null ? targetAiNode.getChatRoom().getId() : null;
            if (!Objects.equals(targetRoomId, roomId)) {
                throw new IllegalArgumentException("遺紐??몃뱶媛 ?대떦 ??붾갑???띠븯吏 ?딆뒿?덈떎.");
            }
        }

        int nextDepth = targetAiNode == null ? 0 : targetAiNode.getDepth() + 1;
        int depthDiff = nextDepth - sourceUserNode.getDepth();
        String fallbackTitle = trimToLength(defaultString(sourceUserNode.getContent()).replaceAll("\\s+", " "), 24);
        String fixedTitle = trimToLength(defaultString(nodeTitle).isBlank() ? fallbackTitle : nodeTitle, 120);

        sourceUserNode.updateTreePlacement(targetAiNode, nextDepth);
        sourceUserNode.updateTreeMetadata(fixedTitle, sourceUserNode.getLevel1Topic(), sourceUserNode.getLevel2Topic());
        chatMessageRepository.save(sourceUserNode);

        if (depthDiff != 0) {
            updateChildrenDepthRecursively(sourceUserNode.getId(), depthDiff);
        }

        log.info("Forced node placement: nodeId={} parentId={} title={}", nodeId, parentId, fixedTitle);
    }

    private void updateChildrenDepthRecursively(Long parentId, int depthDiff) {
        List<ChatMessage> children = chatMessageRepository.findByParentId(parentId);
        for (ChatMessage child : children) {
            child.updateDepth(child.getDepth() + depthDiff);
            chatMessageRepository.save(child); // 🌟 DB 강제 저장(UPDATE)
            updateChildrenDepthRecursively(child.getId(), depthDiff);
        }
    }

    // 대상(targetId)이 나(parentId)의 핏줄(자손)인지 확인하는 헬퍼 메서드
    private boolean isDescendant(Long parentId, Long targetId) {
        List<ChatMessage> children = chatMessageRepository.findByParentId(parentId);
        for (ChatMessage child : children) {
            if (child.getId().equals(targetId)) return true;
            if (isDescendant(child.getId(), targetId)) return true;
        }
        return false;
    }

    @Transactional
    public Long rebuildConversation(ConversationRebuildRequest request) {
        // 1. 새로운 ChatRoom 생성 (재구성된 방)
        ChatMessage selectedNode = chatMessageRepository.findById(request.getSelectedNodeId())
                .orElseThrow(() -> new IllegalArgumentException("노드를 찾을 수 없습니다."));

        String nodeName = selectedNode.getNodeTitle();
        if (nodeName == null || nodeName.trim().isEmpty()) {
            String content = selectedNode.getContent();
            if (content != null && content.length() > 10) {
                nodeName = content.substring(0, 10) + "...";
            } else {
                nodeName = content != null ? content : "새 대화";
            }
        }

        ChatRoom newRoom = ChatRoom.builder()
                .title("재구성: " + nodeName)
                .build();
        chatRoomRepository.save(newRoom);

        // 2. 포함할 노드 ID 수집 (Set으로 중복 방지)
        Set<Long> targetIds = new HashSet<>();
        ChatMessage pathCursor = selectedNode;
        while (pathCursor != null) {
            targetIds.add(pathCursor.getId());
            pathCursor = pathCursor.getParent();
        }

        for (Long branchId : request.getExtraBranchIds()) {
            collectSubtreeIds(branchId, targetIds);
            ChatMessage branchCursor = chatMessageRepository.findById(branchId).orElse(null);
            while (branchCursor != null) {
                targetIds.add(branchCursor.getId());
                branchCursor = branchCursor.getParent();
            }
        }

        // 3. 노드 복제 및 새 방에 저장
        copyNodesToNewRoom(targetIds, newRoom);
        chatMessageRepository.flush();

        // 🚨 가상 노드 생성 로직은 삭제!

        return newRoom.getId();
    }

    private void collectSubtreeIds(Long parentId, Set<Long> ids) {
        ids.add(parentId);
        List<ChatMessage> children = chatMessageRepository.findByParentId(parentId);
        for (ChatMessage child : children) {
            collectSubtreeIds(child.getId(), ids); // 재귀 탐색
        }
    }
    /**
     * 타겟 ID에 해당하는 노드들을 새로운 방(newRoom)으로 복제하는 내부 메서드
     */
    private void copyNodesToNewRoom(Set<Long> targetIds, ChatRoom newRoom) {
        // 1. 타겟 ID에 해당하는 원본 노드들 모두 DB에서 조회
        List<ChatMessage> originNodes = chatMessageRepository.findAllById(targetIds);

        // [중요] 원본 노드 ID -> 새로 복제된 노드 객체를 매핑하는 Map
        // 부모-자식 관계를 새로운 객체들끼리 다시 맺어주기 위해 반드시 필요합니다.
        Map<Long, ChatMessage> oldIdToNewNodeMap = new HashMap<>();

        // 2. 1차 패스: 노드 정보만 먼저 복제 (부모 객체 연결은 제외)
        for (ChatMessage origin : originNodes) {
            // 민교님이 엔티티에 만들어두신 @Builder 활용
            ChatMessage clonedNode = ChatMessage.builder()
                    .chatRoom(newRoom) // [핵심] 기존 방이 아니라 새로운 방으로 꽂아줍니다.
                    .sender(origin.getSender())
                    .content(origin.getContent())
                    .nodeTitle(origin.getNodeTitle())
                    .level1Topic(origin.getLevel1Topic())
                    .level2Topic(origin.getLevel2Topic())
                    .topicHints(origin.getTopicHints())
                    .depth(origin.getDepth()) // 깊이는 복제해도 그대로 유지됩니다.
                    .build();

            // Map에 원본 ID와 복제본을 저장해 둡니다.
            oldIdToNewNodeMap.put(origin.getId(), clonedNode);
        }

        // 3. 2차 패스: 부모-자식 관계를 '복제된 객체들끼리' 다시 연결
        for (ChatMessage origin : originNodes) {
            // 원본 노드에 부모가 있고, 그 부모도 이번에 같이 복제되는 대상(targetIds)에 포함되어 있다면
            if (origin.getParent() != null && oldIdToNewNodeMap.containsKey(origin.getParent().getId())) {
                ChatMessage clonedNode = oldIdToNewNodeMap.get(origin.getId());
                ChatMessage clonedParent = oldIdToNewNodeMap.get(origin.getParent().getId());

                // 민교님이 엔티티에 만들어두신 메서드를 아주 유용하게 사용합니다!
                clonedNode.updateTreePlacement(clonedParent, clonedNode.getDepth());
            }
        }

        // 4. 완성된 복제 노드들을 한 번에 DB에 저장 (Insert 쿼리 발생)
        chatMessageRepository.saveAll(oldIdToNewNodeMap.values());
    }



    public String extractKnowledge(ConversationRebuildRequest request) {
        // 1. 노드 수집
        ChatMessage selectedNode = chatMessageRepository.findById(request.getSelectedNodeId())
                .orElseThrow(() -> new IllegalArgumentException("노드를 찾을 수 없습니다."));

        Set<Long> targetIds = new HashSet<>();
        ChatMessage pathCursor = selectedNode;
        while (pathCursor != null) {
            targetIds.add(pathCursor.getId());
            pathCursor = pathCursor.getParent();
        }
        for (Long branchId : request.getExtraBranchIds()) {
            collectSubtreeIds(branchId, targetIds);
        }

        // 🚨 이 부분이 지워져서 'messages' 심볼 에러가 났던 겁니다! 다시 추가 완료!
        List<ChatMessage> messages = chatMessageRepository.findAllById(targetIds);
        messages.sort(Comparator.comparing(ChatMessage::getCreatedAt));

        // 2. AI에게 읽힐 "원문 텍스트" 조립 (프론트로는 보내지 않고 AI만 읽음)
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("다음은 사용자가 선택한 대화 트리 내용입니다.\n\n");
        for (ChatMessage msg : messages) {
            String role = msg.getSender() == SenderRole.USER ? "질문" : "답변";
            promptBuilder.append(String.format("[%s]: %s\n", role, msg.getContent()));
        }
        promptBuilder.append("\n\n위 내용을 바탕으로 핵심 지식을 추출하여 리포트를 작성해줘.");

        // 3. AI API 호출 (텍스트 요약만 받아와서 바로 프론트로 리턴)
        return rabbitGuardService.chat(request.getSourceRoomId(), promptBuilder.toString());
    }

    private record RootTopicCheckCacheEntry(RootTopicCheckResponse response, long createdAt) {
    }

    // 조상 추적: Depth 1 (소주제) 노드 찾기
    private ChatMessage getLevel2Ancestor(ChatMessage node) {
        ChatMessage cursor = node;
        while (cursor != null) {
            if (cursor.getDepth() == 1) return cursor;
            cursor = cursor.getParent();
        }
        return null;
    }

    // 조상 추적: Depth 0 (루트 대주제) 노드 찾기
    private ChatMessage getRootAncestor(ChatMessage node) {
        ChatMessage cursor = node;
        while (cursor != null) {
            if (cursor.getDepth() == 0) return cursor;
            cursor = cursor.getParent();
        }
        return node; // 최상단 노드를 찾지 못할 경우 자기 자신 반환 (방어 코드)
    }

    // null 및 빈 문자열 체크 유틸 (있다면 생략)
    private boolean isNotBlank(String text) {
        return text != null && !text.trim().isEmpty();
    }
}
