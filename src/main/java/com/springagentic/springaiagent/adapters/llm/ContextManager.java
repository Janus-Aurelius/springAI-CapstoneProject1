package com.springagentic.springaiagent.adapters.llm;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ContextManager {

    private final Encoding encoding;

    public ContextManager() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public List<Message> truncate(List<Message> messages, int maxTokens) {
        // Use a 10% safety buffer
        int budget = (int) (maxTokens * 0.9);
        
        int currentTokens = countTokens(messages);
        if (currentTokens <= budget) {
            return messages;
        }

        List<Message> result = new ArrayList<>(messages);
        
        // We need to keep System message (index 0) and at least the last 2 messages if they exist
        int keepLastN = Math.min(2, result.size() - 1);
        
        // Prune from the middle (starting after system message until we fit the budget)
        // Overhead of 4 tokens per message is added in countTokens, so we subtract it here too.
        while (result.size() > (1 + keepLastN) && currentTokens > budget) {
            Message removed = result.remove(1);
            currentTokens -= (countTokens(removed.getText()) + 4);
        }

        return result;
    }

    public int countTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += countTokens(m.getText());
            // Add overhead for message structure
            total += 4; 
        }
        total += 3; // Final overhead
        return total;
    }

    public int countTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return encoding.countTokens(text);
    }
}
