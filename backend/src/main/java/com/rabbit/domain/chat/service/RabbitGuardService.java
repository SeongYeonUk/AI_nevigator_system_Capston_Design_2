package com.rabbit.domain.chat.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;

public interface RabbitGuardService {
    @SystemMessage({
            "너는 학습자의 이탈을 방지하는 '래빗홀 가드' AI야.",
            "사용자의 질문이 원래 학습 목표와 관련이 있는지 판단해서 답변해줘."
    })
    String chat(@MemoryId Long chatRoomId, String userMessage);


}

