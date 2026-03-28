package com.rabbit.domain.chat.Repository;

import com.rabbit.domain.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByChatRoomIdOrderByCreatedAtAsc(Long roomId);

    @Modifying
    @Query("update ChatMessage m set m.parent = null where m.chatRoom.id = :roomId")
    void clearParentByRoomId(@Param("roomId") Long roomId);

    @Modifying
    void deleteAllByChatRoomId(Long roomId);

    // 특정 부모를 가진 자식 노드들 찾기
    List<ChatMessage> findByParentId(Long parentId);

}
