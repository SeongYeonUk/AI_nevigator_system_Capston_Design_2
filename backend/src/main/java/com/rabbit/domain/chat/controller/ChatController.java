package com.rabbit.domain.chat.controller;

import com.rabbit.domain.chat.dto.*;
import com.rabbit.domain.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    @DeleteMapping("/room/{roomId}")
    public ResponseEntity<Void> deleteRoom(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId
    ) {
        chatService.deleteRoom(authorization, roomId);
        return ResponseEntity.ok().build();
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
            ChatResponse response = chatService.ask(
                    authorization,
                    request.getRoomId(),
                    request.getParentId(),
                    request.getMessage()
            );
            return ResponseEntity.ok(response);
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

    @GetMapping("/room/{roomId}/tree")
    public ConversationTreeResponse getConversationTree(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId
    ) {
        return chatService.getConversationTree(authorization, roomId);
    }

    @GetMapping("/node/{nodeId}/insight")
    public NodeInsightResponse getNodeInsight(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long nodeId
    ) {
        return chatService.getNodeInsight(nodeId);
    }

    @GetMapping("/room/{roomId}/node/{nodeId}/child-recommendations")
    public ChildNodeRecommendationResponse getChildNodeRecommendations(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId,
            @PathVariable Long nodeId
    ) {
        return chatService.getDirectChildRecommendations(authorization, roomId, nodeId);
    }

    @PostMapping("/room/{roomId}/node/{nodeId}/recommended-child")
    public ResponseEntity<?> createRecommendedChildNode(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId,
            @PathVariable Long nodeId,
            @RequestBody CreateRecommendedChildNodeRequest request
    ) {
        try {
            ChatResponse response = chatService.createRecommendedDirectChild(
                    authorization,
                    roomId,
                    nodeId,
                    request != null ? request.getSubtopic() : ""
            );
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("추천 하위 노드 생성 중 오류가 발생했습니다: " + e.getMessage());
        }
    }


    @DeleteMapping("/room/{roomId}/node/{nodeId}")
    public ResponseEntity<?> deleteNode(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId,
            @PathVariable Long nodeId
    ) {
        try {
            chatService.deleteNodeAndSubtree(authorization, roomId, nodeId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("노드 삭제 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    @PutMapping("/room/{roomId}/node/{nodeId}/move")
    public ResponseEntity<?> moveNode(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId,
            @PathVariable Long nodeId,
            @RequestBody MoveNodeRequest request
    ) {
        try {
            chatService.moveNode(authorization, roomId, nodeId, request.getNewParentId());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("노드 이동 중 오류가 발생했습니다: " + e.getMessage());
        }
    }
}
