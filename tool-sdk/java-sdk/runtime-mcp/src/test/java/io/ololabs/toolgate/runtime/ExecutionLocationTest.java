// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionLocationTest {
    private final ClassLoader tools = Fixtures.compile(Map.of("Mixed.java", """
        import io.ololabs.toolgate.sdk.annotations.*;
        @ToolPackage(id="acme.mixed",name="Mixed tools",publisher="Acme")
        public class Mixed {
          public record Result(String message) {}
          @ClientTool(name="client",title="Client",description="Only on clients",stateless=true)
          public Result client() { return new Result("client"); }
          @ServerTool(name="server",title="Server",description="On hosts and clients",stateless=true)
          public Result server() { return new Result("server"); }
        }
        """));

    private String call(String target, String name) throws Exception {
        var input = Fixtures.JSON.createObjectNode().put("protocolVersion", 2).put("toolId", "acme.mixed/" + name);
        input.putObject("arguments"); input.putObject("globals"); input.putObject("claims"); input.putArray("secretHandles");
        var output = new ByteArrayOutputStream();
        Governed.run(tools, Map.of("TOOLGATE_EXECUTION_TARGET", target),
                new ByteArrayInputStream((input + "\n").getBytes(StandardCharsets.UTF_8)), output, new PrintStream(new ByteArrayOutputStream()));
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test void serverLoadsOnlyEligibleExportFromMixedPackage() throws Exception {
        assertTrue(call("SERVER", "server").contains("server"));
        assertTrue(call("SERVER", "client").contains("TOOL_NOT_FOUND"));
    }
    @Test void clientMayExecuteBothKindsAndInvalidTargetsFailClosed() throws Exception {
        assertTrue(call("CLIENT", "client").contains("client"));
        assertTrue(call("CLIENT", "server").contains("server"));
        assertTrue(call("invalid", "server").contains("INVALID_TARGET"));
    }
}
