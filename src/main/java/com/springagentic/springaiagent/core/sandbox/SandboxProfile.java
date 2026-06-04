package com.springagentic.springaiagent.core.sandbox;

public enum SandboxProfile {
    COMPUTE,   // --network none. For Python, math, text processing.
    FETCH      // sandbox_net + Squid proxy. For web/API fetch tools.
}
