package com.rabbit.domain.chat.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MoveNodeRequest {
    private Long newParentId;
}
