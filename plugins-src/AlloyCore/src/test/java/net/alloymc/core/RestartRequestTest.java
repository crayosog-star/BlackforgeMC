package net.alloymc.core;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for /alloy restart (no server needed). */
class RestartRequestTest {

    @Test void oneServer() {
        RestartRequest.Parsed p = RestartRequest.parse(new String[] {"survival"}, "hub");
        assertEquals(List.of("survival"), p.servers());
        assertFalse(p.now());
    }

    @Test void allAndNow() {
        RestartRequest.Parsed p = RestartRequest.parse(new String[] {"ALL", "now"}, "hub");
        assertEquals(RestartRequest.SERVERS, p.servers());
        assertTrue(p.now());
    }

    @Test void thisMeansThisServer() {
        assertEquals(List.of("arcade"), RestartRequest.parse(new String[] {"this"}, "arcade").servers());
    }

    @Test void unknownOrMissingIsRejected() {
        assertNull(RestartRequest.parse(new String[] {}, "hub"));
        assertNull(RestartRequest.parse(new String[] {"lobby"}, "hub"));
        assertNull(RestartRequest.parse(new String[] {"this"}, "backend"));
    }

    @Test void lineFormat() {
        RestartRequest.Parsed p = RestartRequest.parse(new String[] {"survival", "now"}, "hub");
        assertEquals("survival now Colin 123", RestartRequest.line(p, "Colin", 123));
        assertEquals("survival now CONSOLE_user 5", RestartRequest.line(p, "CONSOLE user", 5));
    }

    @Test void findsTheNetworkFolderAndWritesTheRequest() throws Exception {
        File root = Files.createTempDirectory("bfmc").toFile();
        new File(root, "tools").mkdirs();
        Files.writeString(new File(root, "tools/netctl.py").toPath(), "");
        File data = new File(root, "survival/plugins/AlloyCore");
        data.mkdirs();
        assertEquals(root.getCanonicalFile(), RestartRequest.networkRoot(data).getCanonicalFile());
        File req = RestartRequest.write(data, RestartRequest.parse(new String[] {"hub"}, "survival"), "Colin");
        assertTrue(req.getParentFile().getName().equals("restart-requests"));
        assertTrue(Files.readString(req.toPath()).startsWith("hub countdown Colin "));
    }

    @Test void refusesOutsideTheNetworkLayout() throws Exception {
        File data = Files.createTempDirectory("loose").toFile();
        assertNull(RestartRequest.networkRoot(data));
    }
}
