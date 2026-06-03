package com.springagentic.springaiagent.core.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class HybridJTokkitTruncatorTest {

    private final HybridJTokkitTruncator truncator = new HybridJTokkitTruncator();

    @Test
    public void testShortTextNotTruncated() {
        String input = "This is a short observation that is well within the token limit.";
        String result = truncator.truncate(input, 50);
        assertEquals(input, result);
    }

    @Test
    public void testZeroOrNegativeMaxTokens() {
        String input = "Some input text.";
        assertEquals("", truncator.truncate(input, 0));
        assertEquals("", truncator.truncate(input, -10));
    }

    @Test
    public void testNullInput() {
        assertEquals("", truncator.truncate(null, 10));
    }

    @Test
    public void testLargeTextTruncationPattern() {
        // Construct a large text block of numbers
        StringBuilder sb = new StringBuilder();
        sb.append("HEADER: ID, Name, Role\n");
        for (int i = 1; i <= 500; i++) {
            sb.append(i).append(", User").append(i).append(", Developer\n");
        }
        sb.append("FOOTER: Total count is 500 users.\n");
        String largeInput = sb.toString();

        // Truncate to a limit of 200 tokens (forces Head/Tail pruning with enough budget)
        String result = truncator.truncate(largeInput, 200);

        assertNotNull(result);
        assertTrue(result.contains("HEADER: ID, Name, Role"));
        assertTrue(result.contains("FOOTER: Total count is 500 users."));
        assertTrue(result.contains("SYSTEM WARNING: Payload exceeded"));
        assertTrue(result.contains("MIDDLE"));
        
        // Assert that the final result length is significantly smaller than the input length
        assertTrue(result.length() < largeInput.length());
    }
}
