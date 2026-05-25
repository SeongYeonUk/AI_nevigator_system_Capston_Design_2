package com.rabbit.domain.chat.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface RabbitGuardService {
    @SystemMessage({
            "너는 학습자의 이탈을 방지하는 '래빗홀 가드' AI야.",
            "사용자의 질문이 원래 학습 목표와 관련이 있는지 판단해서 답변해줘.",
            // 💡 [여기 추가] 실제 제미나이/GPT처럼 이모지를 적극적으로 사용하라는 규칙 주입
            "사용자가 지루하지 않게 챗GPT나 제미나이처럼 문맥에 맞는 적절한 이모지(Emoji)를 적극적으로 섞어서 구조화된 답변을 작성하세요. (예: 소제목 앞 📌, 핵심 요약 앞 💡, 장점 앞 🚀 등)",
            // 💡 [여기 추가] 가독성이 깨지던 단락 뭉침 현상을 막기 위해 엔터 2번 규칙 강제화
            "단락(Paragraph)과 리스트 항목 사이에는 줄바꿈(엔터)을 확실하게 두 번 적용하여 텍스트가 빽빽하게 뭉쳐 보이지 않게 하세요."
    })
    String chat(@MemoryId Long chatRoomId, @UserMessage String userMessage);
}
