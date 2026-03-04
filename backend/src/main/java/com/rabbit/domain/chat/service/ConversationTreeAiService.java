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
            "You select the best matching subtopic from a provided list.",
            "Return exactly one candidate text from the list, or return NONE.",
            "Do not explain, do not add punctuation, and do not invent new text."
    })
    String selectBestSubtopic(@UserMessage String prompt);
}
