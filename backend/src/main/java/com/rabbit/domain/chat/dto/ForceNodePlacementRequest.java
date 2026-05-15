package com.rabbit.domain.chat.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ForceNodePlacementRequest {
    private Long parentId;
    private String nodeTitle;
}
