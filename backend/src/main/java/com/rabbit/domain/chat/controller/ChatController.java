package com.rabbit.domain.chat.controller;

import com.rabbit.domain.chat.Repository.ChatMessageRepository;
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
    private final ChatMessageRepository chatMessageRepository;

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
                    request.getMessage(),
                    request.isForceCreateUnrelated(),
                    request.isSkipRootTopicGuard(),
                    request.getRootTopic()
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

    @PostMapping("/room/{roomId}/root-topic-check")
    public ResponseEntity<?> checkRootTopic(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId,
            @RequestBody RootTopicCheckRequest request
    ) {
        try {
            return ResponseEntity.ok(chatService.checkRootTopicRelation(authorization, roomId, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
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
    // 대화 재구성 API 추가!
    @PutMapping("/room/{roomId}/node/{nodeId}/force-placement")
    public ResponseEntity<?> forceNodePlacement(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long roomId,
            @PathVariable Long nodeId,
            @RequestBody ForceNodePlacementRequest request
    ) {
        try {
            chatService.forceNodePlacement(
                    authorization,
                    roomId,
                    nodeId,
                    request.getParentId(),
                    request.getNodeTitle()
            );
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("?몃뱶 ?꾩튂 怨좎젙 以??ㅻ쪟媛 諛쒖깮?덉뒿?덈떎: " + e.getMessage());
        }
    }

    @PostMapping("/room/rebuild")
    public ResponseEntity<Long> rebuildRoom(
            @RequestHeader("Authorization") String authorization,
            @RequestBody ConversationRebuildRequest request
    ) {
        try {
            // chatService에 인증(authorization) 정보도 넘겨야 한다면 매개변수를 추가해주세요.
            // 현재 chatService.rebuildConversation(request) 로 구현되어 있으니 그대로 호출합니다.
            Long newRoomId = chatService.rebuildConversation(request);
            return ResponseEntity.ok(newRoomId);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    // 경로 지식 추출 API
    @PostMapping("/room/extract")
    public ResponseEntity<String> extractKnowledge( // 리턴 타입을 String으로 변경
                                                    @RequestHeader("Authorization") String authorization,
                                                    @RequestBody ConversationRebuildRequest request
    ) {
        try {
            // 1. 여기서 ChatService의 메서드를 호출해서 결과를 받아옵니다.
            String result = chatService.extractKnowledge(request);

            // 2. 받아온 결과(result)를 응답으로 내려줍니다. (response -> result)
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            e.printStackTrace(); // 서버 로그에서 에러 원인을 보기 위해 추가
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("에러 발생: " + e.getMessage());
        }
    }




}
