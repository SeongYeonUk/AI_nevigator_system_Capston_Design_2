package com.rabbit.domain.chat.controller;

import com.rabbit.domain.chat.dto.ChatHistoryResponse;
import com.rabbit.domain.chat.dto.ChatRequest;
import com.rabbit.domain.chat.dto.ChatResponse;
import com.rabbit.domain.chat.dto.ChatRoomResponse;
import com.rabbit.domain.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:5500", "http://127.0.0.1:5500"})
public class ChatController {

    private final ChatService chatService;

    @GetMapping("/rooms")
    public List<ChatRoomResponse> getRooms(@RequestHeader("Authorization") String authorization) {
        return chatService.getRoomList(authorization);
    }

    @PostMapping("/room")
    public Long createRoom(
            @RequestHeader("Authorization") String authorization,
            @RequestParam String title
    ) {
        return chatService.createRoom(authorization, title);
    }

    @PutMapping("/room/{roomId}/title")
    public ResponseEntity<String> updateRoomTitle(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId,
            @RequestParam String title
    ) {
        try {
            chatService.updateRoomTitle(authorization, roomId, title);
            return ResponseEntity.ok("ok");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<?> ask(
            @RequestHeader("Authorization") String authorization,
            @RequestBody ChatRequest request
    ) {
        try {
            String aiAnswer = chatService.ask(authorization, request.getRoomId(), request.getMessage());
            return ResponseEntity.ok(new ChatResponse(aiAnswer));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("AI 응답 생성 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    @GetMapping("/room/{roomId}/history")
    public List<ChatHistoryResponse> getHistory(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId
    ) {
        return chatService.getHistory(authorization, roomId);
    }
}
