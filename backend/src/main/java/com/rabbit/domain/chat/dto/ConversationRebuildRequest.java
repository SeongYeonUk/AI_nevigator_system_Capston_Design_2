package com.rabbit.domain.chat.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class ConversationRebuildRequest {
    private Long sourceRoomId;        // 원본 대화방 ID
    private Long selectedNodeId;      // 선택된 중심 노드 ID
    private List<Long> extraBranchIds; // 추가로 선택한 가지들의 루트 노드 ID 목록
}
