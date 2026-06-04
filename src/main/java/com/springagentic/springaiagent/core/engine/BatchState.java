package com.springagentic.springaiagent.core.engine;

public enum BatchState {
    ACTIVE,      // Steps executing, rollback is possible
    MUTATED,     // A mutating tool has run; baseline rollback is now blocked
    INVALIDATED  // A step failed; all remaining sibling threads must abort
}
