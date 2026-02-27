package com.rabbit.domain.chat.service;

import com.rabbit.domain.chat.Repository.ChatMessageRepository;
import com.rabbit.domain.chat.Repository.ChatRoomRepository;
import com.rabbit.domain.chat.dto.ChatHistoryResponse;
import com.rabbit.domain.chat.dto.ChatRoomResponse;
import com.rabbit.domain.chat.entity.ChatMessage;
import com.rabbit.domain.chat.entity.ChatRoom;
import com.rabbit.domain.chat.enums.SenderRole;
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


    @Transactional
    public Long createRoom(String title) {
        ChatRoom room = ChatRoom.builder()
                .title(title)
                .build();
        return chatRoomRepository.save(room).getId();
    }

    @Transactional(readOnly = true)
    public List<ChatRoomResponse> getRoomList() {
        return chatRoomRepository.findAll()
                .stream()
                .map(room -> new ChatRoomResponse(room.getId(), room.getTitle(), room.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @Transactional
    public String ask(Long roomId, String userMessage) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("방을 찾을 수 없습니다."));

        //  유저 메시지 저장
        chatMessageRepository.save(ChatMessage.builder()
                .chatRoom(room)
                .sender(SenderRole.USER)
                .content(userMessage)
                .build());

        //  AI 답변 생성 호출
        String aiAnswer = rabbitGuardService.chat(roomId, userMessage);

        //  AI 답변 저장
        chatMessageRepository.save(ChatMessage.builder()
                .chatRoom(room)
                .sender(SenderRole.AI)
                .content(aiAnswer)
                .build());

        return aiAnswer;
    }

    @Transactional(readOnly = true)
    public List<ChatHistoryResponse> getHistory(Long roomId) {
        return chatMessageRepository.findByChatRoomIdOrderByCreatedAtAsc(roomId)
                .stream()
                .map(msg -> new ChatHistoryResponse(msg.getContent(), msg.getSender(), msg.getCreatedAt()))
                .collect(Collectors.toList());
    }
}