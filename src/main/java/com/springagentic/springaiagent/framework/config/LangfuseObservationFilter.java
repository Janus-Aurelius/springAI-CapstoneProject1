package com.springagentic.springaiagent.framework.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LangfuseObservationFilter implements ObservationFilter {

    @Override
    public Observation.Context map(Observation.Context context) {
        if (context instanceof ChatModelObservationContext chatContext) {
            Prompt prompt = chatContext.getRequest();
            if (prompt != null) {
                List<Message> messages = prompt.getInstructions();
                if (messages != null && !messages.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Message msg : messages) {
                        sb.append("[").append(msg.getMessageType()).append("]: ").append(msg.getText()).append("\n");
                    }
                    context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.content.prompt", sb.toString().trim()));
                }
            }

            ChatResponse response = chatContext.getResponse();
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                String text = response.getResult().getOutput().getText();
                if (text != null && !text.isBlank()) {
                    context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.completion", text.trim()));
                }
            }
        }
        return context;
    }
}
