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
}
