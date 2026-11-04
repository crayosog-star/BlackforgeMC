package net.blackforge.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Agent names go straight into MiniMessage text and saved item data, so only plain names may pass. */
class AgentNameTest {
    @Test void plainNamesPass() {
        assertTrue(RobotRules.validName("Bob"));
        assertTrue(RobotRules.validName("R2D2"));
        assertTrue(RobotRules.validName("a"));
        assertTrue(RobotRules.validName("abcdefghijklmnop"));
    }

    @Test void anythingElseFails() {
        assertFalse(RobotRules.validName(null));
        assertFalse(RobotRules.validName(""));
        assertFalse(RobotRules.validName("abcdefghijklmnopq"));
        assertFalse(RobotRules.validName("<red>Bob"));
        assertFalse(RobotRules.validName("Bob;9"));
        assertFalse(RobotRules.validName("Agent Bob"));
    }
}
