package com.rabbit.domain.chat.dto;

import com.rabbit.domain.chat.entity.ChatRoom;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@Builder
public class ChatRoomResponse {
    private Long id;
    private String title;
    private LocalDateTime createdAt;

    // Entity -> DTO 변환 메서드 (Static Factory Method)
    public static ChatRoomResponse from(ChatRoom room) {
        return ChatRoomResponse.builder()
                .id(room.getId())
                .title(room.getTitle())
                .createdAt(room.getCreatedAt())
                .build();
    }
}