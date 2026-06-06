package com.springagentic.springaiagent.adapters.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import java.util.List;

public interface LlmRouter {
    ChatResponse generate(List<Message> messages, TaskType taskType);
}
