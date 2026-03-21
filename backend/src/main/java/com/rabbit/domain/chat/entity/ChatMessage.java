package com.rabbit.domain.chat.entity;

import com.rabbit.domain.chat.enums.SenderRole;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private ChatMessage parent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private ChatRoom chatRoom;

    @Enumerated(EnumType.STRING)
    private SenderRole sender;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 120)
    private String nodeTitle;

    @Column(length = 120)
    private String level1Topic;

    @Column(length = 120)
    private String level2Topic;

    @Column(columnDefinition = "TEXT")
    private String topicHints;

    private LocalDateTime createdAt = LocalDateTime.now();

    private int depth;

    @Builder
    public ChatMessage(
            ChatMessage parent,
            ChatRoom chatRoom,
            SenderRole sender,
            String content,
            String nodeTitle,
            String level1Topic,
            String level2Topic,
            String topicHints,
            int depth
    ) {
        this.parent = parent;
        this.chatRoom = chatRoom;
        this.sender = sender;
        this.content = content;
        this.nodeTitle = nodeTitle;
        this.level1Topic = level1Topic;
        this.level2Topic = level2Topic;
        this.topicHints = topicHints;
        this.depth = depth;
    }

    public void updateTopicHints(String topicHints) {
        this.topicHints = topicHints;
    }

    public void updateTreePlacement(ChatMessage parent, int depth) {
        this.parent = parent;
        this.depth = depth;
    }

    public void updateTreeMetadata(String nodeTitle, String level1Topic, String level2Topic) {
        this.nodeTitle = nodeTitle;
        this.level1Topic = level1Topic;
        this.level2Topic = level2Topic;
    }

    public void updateDepth(int depth) {
        this.depth = depth;
    }
}
