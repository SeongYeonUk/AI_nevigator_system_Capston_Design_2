package com.rabbit.domain.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ExtractResponse {
    private String summary; // AI가 요약해준 마크다운 결과물
}
