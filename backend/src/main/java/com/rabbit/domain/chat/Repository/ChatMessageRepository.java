package com.rabbit.domain.chat.Repository;
import com.rabbit.domain.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    // 특정 방의 모든 메시지를 시간순으로 가져오기
    List<ChatMessage> findByChatRoomIdOrderByCreatedAtAsc(Long roomId);
}