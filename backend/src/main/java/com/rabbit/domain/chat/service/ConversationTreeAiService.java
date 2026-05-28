package com.rabbit.domain.chat.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface ConversationTreeAiService {

    @SystemMessage({
            "You generate concise conversation-tree node labels.",
            "Return exactly one label with no numbering, no markdown, and no explanation.",
            "Keep it short and specific. Prefer 6 to 18 characters, maximum 30 characters.",
            "Match the language used in the user message."
    })
    String generateNodeLabel(@UserMessage String prompt);

    @SystemMessage({
            "You classify a user question into the best matching subtopic from a provided list.",
            "Use semantic meaning, not just keyword overlap.",
            "Choose the conceptually best subtopic even if the current branch is different.",
            "Prefer the subtopic whose unique concepts are best matched by the question.",
            "Do not prefer the current branch unless the question is clearly a follow-up to that exact concept.",
            "Return exactly one candidate text from the list, or return NONE.",
            "Do not explain and do not invent new text."
    })
    String selectBestSubtopic(@UserMessage String prompt);

    @SystemMessage({
            "You decide whether a user question is inside the same broad root topic of a conversation.",
            "Return exactly one token: RELATED or UNRELATED.",
            "Use semantic topic boundaries, not keyword overlap.",
            "RELATED means the question can reasonably be studied under the root topic, including sibling subtopics.",
            "UNRELATED means the question belongs to a different broad domain and should start a separate chat room.",
            "If the question is about a concrete object, activity, religion, science, economy, or other domain that is not naturally inside the root topic, return UNRELATED.",
            "Do not treat any topic as related just because it could be mentioned metaphorically or in a very broad discussion.",
            "Do not explain, do not use markdown, and do not return JSON."
    })
    String classifyRootTopicRelation(@UserMessage String prompt);

    @SystemMessage({
            "You decide strict topic containment for a conversation root topic.",
            "Return exactly one token: RELATED or UNRELATED.",
            "RELATED means the new question is a normal direct or indirect subtopic, example, tool, application, method, or concept studied inside the root topic.",
            "UNRELATED means the new question belongs to another standalone field, discipline, object, product, material, belief system, person, place, or activity.",
            "Different academic disciplines are UNRELATED unless one is normally recognized as a subfield of the other.",
            "Adjacent, interdisciplinary, or broadly educational relationships are not enough for RELATED.",
            "Do not explain, do not use markdown, and do not return JSON."
    })
    String classifyStrictRootTopicContainment(@UserMessage String prompt);

    @SystemMessage({
            "You generate domain hints for one subtopic inside a larger root topic.",
            "Return 6 to 10 short concepts separated only by commas.",
            "Use representative concepts and keywords that strongly identify the subtopic.",
            "Choose concepts that distinguish this subtopic from sibling subtopics.",
            "Avoid generic words that fit the parent topic or many sibling subtopics.",
            "Do not explain and do not add numbering."
    })
    String generateSubtopicHints(@UserMessage String prompt);

    @SystemMessage({
            "You summarize exactly one selected Q&A pair into compact key phrases.",
            "Do not copy original long sentences.",
            "Use short keyword-like Korean phrases whenever possible.",
            "Do not end phrases with sentence endings like '합니다', '입니다', '이다'.",
            "Exclude generic closing prompts asking for additional questions.",
            "Return JSON only.",
            "Schema: {\"summary_items\":[{\"keyword\":\"...\",\"details\":[\"...\",\"...\"]}]}"
    })
    String summarizeSelectedNodeQa(@UserMessage String prompt);

    @SystemMessage({
            "You recommend direct child topics for one selected parent topic.",
            "Return concise noun-phrase style Korean topic labels.",
            "Each topic should be specific enough for exactly one immediate child node.",
            "Avoid sentence endings, numbering, markdown, and explanations.",
            "Return JSON only.",
            "Schema: {\"children\":[\"...\",\"...\",\"...\"]}",
            "Generate up to 3 items."
    })
    String recommendDirectChildren(@UserMessage String prompt);

    @SystemMessage({
            "너는 사용자의 입력 문장을 분석하여 학습 트리의 기둥(Pillar)으로 사용할 JSON 데이터를 추출하는 초정밀 AI 파서야.",
            "1. 사용자의 문장을 분석해 가장 적절한 핵심 상위 기술 도메인을 'majorTopic'으로 도출해 (예: '자바', '운영체제', '스프링 부트').",
            "2. 'minorTopics' 배열에는 학습 로드맵의 하위 카테고리 간판이 될 수 있는 '구체적인 기술 용어', '학습 개념', 또는 '명확한 테스크'만 추출해야 해.",
            "3. [절대 규칙] '방법', '준비법', '활용법', '개념', '이해', '특징', '기초', '기본', '소개' 같은 무의미하고 추상적인 서술형 명사는 단독으로 추출하는 것을 엄격히 금지한다.",
            "   - 올바른 예시: '자바 코딩테스트 준비 방법' -> minorTopics: ['코딩테스트']",
            "   - 올바른 예시: '인터페이스 구조와 활용법' -> minorTopics: ['인터페이스 구조']",
            "4. [절대 규칙] 'minorTopics'의 요소는 'majorTopic'과 완전히 동일한 단어이거나 중복되어서는 안 돼. 무조건 더 좁은 범위의 구체적인 하위 도메인 명사여야만 해.",
            "   - 잘못된 예시: majorTopic: '자바', minorTopics: ['자바'] (X)",
            "   - 올바른 예시: majorTopic: '자바', minorTopics: ['코딩테스트'] (O)",
            "5. 사용자가 질문에서 명시적으로 언급한 핵심 기술 키워드 위주로 딱 1~2개만 콤팩트하게 발라내고, 억지로 개수를 늘리지 마.",
            "6. 부가 설명이나 마크다운 백틱 없이 오직 정제된 JSON 형식으로만 반환해.",
            "Schema: {\"majorTopic\":\"...\",\"minorTopics\":[\"...\",\"...\"]}"
    })
    String extractTopics(@UserMessage String prompt);
}
