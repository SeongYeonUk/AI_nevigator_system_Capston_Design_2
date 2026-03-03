package com.rabbit.domain.chat.service;

import com.rabbit.domain.chat.Repository.ChatMessageRepository;
import com.rabbit.domain.chat.Repository.ChatRoomRepository;
import com.rabbit.domain.chat.dto.ChatHistoryResponse;
import com.rabbit.domain.chat.dto.ChatRoomResponse;
import com.rabbit.domain.chat.dto.ChatResponse;
import com.rabbit.domain.chat.dto.NodeInsightResponse;
import com.rabbit.domain.chat.entity.ChatMessage;
import com.rabbit.domain.chat.entity.ChatRoom;
import com.rabbit.domain.chat.enums.SenderRole;
import com.rabbit.domain.user.repository.UserRepository;
import com.rabbit.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final RabbitGuardService rabbitGuardService;
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
    public void updateRoomTitle(String authorization, Long roomId, String title) {
        validateAuthorization(authorization);

        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("제목은 비워둘 수 없습니다.");
        }

        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다."));
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
        validateAuthorization(authorization); //

        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다."));

        // 1. 부모 노드 확인 및 Depth 계산
        ChatMessage parentNode = (parentId != null)
                ? chatMessageRepository.findById(parentId).orElse(null)
                : null;

        // 부모가 없으면 Depth 0, 있으면 부모의 Depth + 1
        int currentDepth = (parentNode == null) ? 0 : parentNode.getDepth() + 1;

        // 2. 유저 메시지 저장 (부모 정보와 Depth 기록)
        ChatMessage userSaved = chatMessageRepository.save(ChatMessage.builder()
                .chatRoom(room)
                .parent(parentNode)
                .sender(SenderRole.USER)
                .content(userMessage)
                .depth(currentDepth)
                .build());

        // 3. AI 답변 생성 (기존 래빗홀 가드 서비스 활용)
        String aiAnswer = rabbitGuardService.chat(roomId, userMessage);

        // 4. AI 메시지 저장 (유저 메시지를 부모로 삼음)
        chatMessageRepository.save(ChatMessage.builder()
                .chatRoom(room)
                .parent(userSaved)
                .sender(SenderRole.AI)
                .content(aiAnswer)
                .depth(currentDepth)
                .build());

        // 5. [핵심] 답변과 유저 메시지의 진짜 ID를 함께 리턴
        return new ChatResponse(aiAnswer, userSaved.getId());
    }
    @Transactional(readOnly = true)
    public List<ChatHistoryResponse> getHistory(String authorization, Long roomId) {
        validateAuthorization(authorization); //

        return chatMessageRepository.findByChatRoomIdOrderByCreatedAtAsc(roomId)
                .stream()
                .map(ChatHistoryResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public NodeInsightResponse getNodeInsight(Long nodeId) {
        ChatMessage node = chatMessageRepository.findById(nodeId)
                .orElseThrow(() -> new RuntimeException("노드를 찾을 수 없습니다."));

        // 족보(Parent Path) 생성 알고리즘
        String parentPath = "없음";
        if (node.getDepth() > 0) {
            StringBuilder sb = new StringBuilder("n");
            // 루트부터 현재 층(Depth) 직전까지 올라가며 인덱스(1, 3, 5...)를 붙임
            for (int i = 0; i < node.getDepth(); i++) {
                int userIndex = (i * 2) + 1;
                sb.append("_").append(userIndex);
            }
            parentPath = sb.toString();
        }

        // 경로 상태 막대 (%) - 최대 7단계 기준
        double ratio = Math.min(100, ((double) node.getDepth() / 7) * 100);

        return NodeInsightResponse.builder()
                .title(node.getContent().substring(0, Math.min(node.getContent().length(), 8)))
                .depth(node.getDepth())
                .parentPath(parentPath)
                .progressRatio(ratio)
                .alertMessage(node.getDepth() >= 5 ? "주의: 경로가 깊습니다!" : "안정적입니다.")
                .build();
    }

    private void validateAuthorization(String rawToken) {
        String token = stripBearer(rawToken);
        if (!jwtTokenProvider.validateToken(token)) {
            throw new IllegalArgumentException("유효하지 않은 토큰입니다.");
        }

        String loginId = jwtTokenProvider.getLoginId(token);
        if (!userRepository.existsByLoginId(loginId)) {
            throw new IllegalArgumentException("사용자를 찾을 수 없습니다.");
        }
    }

    private String stripBearer(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("인증 토큰이 없습니다.");
        }
        if (rawToken.startsWith("Bearer ")) {
            return rawToken.substring(7).trim();
        }
        return rawToken.trim();
    }
}
