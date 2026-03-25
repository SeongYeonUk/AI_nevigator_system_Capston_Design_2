package com.rabbit.domain.chat.service;

import com.rabbit.domain.chat.entity.ChatMessage;
import com.rabbit.domain.chat.enums.SenderRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
/*
class ConversationTreePlannerServiceTest {

    @Test
    void depthZeroUsesExplicitRootTopic() {
        ConversationTreePlannerService service = new ConversationTreePlannerService(
                new FakeConversationTreeAiService("ignored")
        );

        ConversationTreePlannerService.TreePlan plan = service.planNode(
                List.of(),
                null,
                0,
                "대주제: AI 챗봇 설계"
        );

        assertEquals("AI 챗봇 설계", plan.level1Topic());
        assertEquals("AI 챗봇 설계", plan.nodeTitle());
        assertEquals("Subtopic", plan.level2Topic());
    }

    @Test
    void depthOneUsesExplicitSubTopicAndKeepsExistingRoot() {
        ConversationTreePlannerService service = new ConversationTreePlannerService(
                new FakeConversationTreeAiService("ignored")
        );
        ChatMessage rootUser = userMessage(null, 0, "첫 루트 질문", "AI Navigator", null);

        ConversationTreePlannerService.TreePlan plan = service.planNode(
                List.of(rootUser),
                null,
                1,
                "소주제: 벡터 검색 성능"
        );

        assertEquals("AI Navigator", plan.level1Topic());
        assertEquals("벡터 검색 성능", plan.level2Topic());
        assertEquals("벡터 검색 성능", plan.nodeTitle());
    }

    @Test
    void depthTwoOrMoreUsesAiNodeTitleAndBranchLevelTwoTopic() {
        ConversationTreePlannerService service = new ConversationTreePlannerService(
                new FakeConversationTreeAiService("세부 실험 계획")
        );

        ChatMessage rootUser = userMessage(null, 0, "대주제: AI 학습 경로", "AI 학습 경로", null);
        ChatMessage rootAi = aiMessage(rootUser, 0, "root answer");

        ChatMessage level2User = userMessage(rootAi, 1, "소주제: RAG 인덱싱", "AI 학습 경로", "RAG 인덱싱");
        ChatMessage level2Ai = aiMessage(level2User, 1, "level2 answer");

        ChatMessage depth2User = userMessage(level2Ai, 2, "임베딩 모델 비교", "AI 학습 경로", "RAG 인덱싱");
        ChatMessage depth2Ai = aiMessage(depth2User, 2, "depth2 answer");

        ConversationTreePlannerService.TreePlan plan = service.planNode(
                List.of(rootUser, rootAi, level2User, level2Ai, depth2User, depth2Ai),
                depth2Ai,
                3,
                "청크 사이즈 실험 방법"
        );

        assertEquals("AI 학습 경로", plan.level1Topic());
        assertEquals("RAG 인덱싱", plan.level2Topic());
        assertEquals("세부 실험 계획", plan.nodeTitle());
    }

    private static ChatMessage userMessage(
            ChatMessage parentAi,
            int depth,
            String content,
            String level1Topic,
            String level2Topic
    ) {
        return ChatMessage.builder()
                .parent(parentAi)
                .sender(SenderRole.USER)
                .content(content)
                .level1Topic(level1Topic)
                .level2Topic(level2Topic)
                .depth(depth)
                .build();
    }

    private static ChatMessage aiMessage(ChatMessage parentUser, int depth, String content) {
        return ChatMessage.builder()
                .parent(parentUser)
                .sender(SenderRole.AI)
                .content(content)
                .depth(depth)
                .build();
    }

    private static class FakeConversationTreeAiService implements ConversationTreeAiService {
        private final String label;

        private FakeConversationTreeAiService(String label) {
            this.label = label;
        }

        @Override
        public String generateNodeLabel(String prompt) {
            return label;
        }

        @Override
        public String selectBestSubtopic(String prompt) {
            return "NONE";
        }
    }
}
*/
