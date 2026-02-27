package com.rabbit.domain.chat.controller;

import com.rabbit.domain.chat.dto.ChatHistoryResponse;
import com.rabbit.domain.chat.dto.ChatRequest;
import com.rabbit.domain.chat.dto.ChatResponse;
import com.rabbit.domain.chat.dto.ChatRoomResponse;
import com.rabbit.domain.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000") // 리액트 서버 허용
public class ChatController {

    private final ChatService chatService;

    // 채팅방 목록 조회 (사이드바용)
    @GetMapping("/rooms")
    public List<ChatRoomResponse> getRooms() {
        return chatService.getRoomList();
    }

    // 새 채팅방 생성
    @PostMapping("/room")
    public Long createRoom(@RequestParam String title) {
        return chatService.createRoom(title);
    }

    // 대화하기
    @PostMapping
    public ChatResponse ask(@RequestBody ChatRequest request) {
        // ChatService 내부에서 RabbitGuardService를 호출하여 답변을 받아옵니다.
        String aiAnswer = chatService.ask(request.getRoomId(), request.getMessage());
        return new ChatResponse(aiAnswer);
    }

    // 이전 대화 내역 불러오기
    @GetMapping("/room/{roomId}/history")
    public List<ChatHistoryResponse> getHistory(@PathVariable Long roomId) {
        return chatService.getHistory(roomId);
    }
}