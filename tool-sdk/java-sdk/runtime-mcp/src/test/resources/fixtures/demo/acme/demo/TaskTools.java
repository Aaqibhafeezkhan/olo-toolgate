// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.demo;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.context.InputRequest;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import java.util.Map;

/** Async tools the task tests run. */
public class TaskTools {
    public record Answer(String name) {}

    @ServerTool(name = "demo.report", title = "Report", description = "Works for a while.", effect = EffectSafety.READ_ONLY, async = true)
    public Map<String, Object> report(@Param(description = "Milliseconds") long ms,
            @Param(description = "Keep going when cancelled", defaultValue = "false") boolean stubborn, ToolContext context) {
        long end = System.currentTimeMillis() + ms;
        int step = 0;
        while (System.currentTimeMillis() < end) {
            context.progress(Math.min(1.0, ++step * 50.0 / Math.max(1, ms)), "step " + step);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                if (!stubborn) return Map.of("stopped", true);
            }
        }
        return Map.of("steps", step, "cancelled", context.isCancelled());
    }

    @ServerTool(name = "demo.ask", title = "Ask", description = "Asks for a name.", effect = EffectSafety.READ_ONLY, async = true)
    public Map<String, Object> ask(ToolContext context) {
        Answer answer = context.requestInput(InputRequest.of("What is your name?",
                Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")), "required", java.util.List.of("name")),
                Answer.class));
        return Map.of("greeting", "Hello, " + answer.name() + "!");
    }

    @ServerTool(name = "demo.refuse", title = "Refuse", description = "Fails as a task.", effect = EffectSafety.READ_ONLY, async = true,
            errors = @ErrorDecl(code = "NOPE", description = "Refused"))
    public Map<String, Object> refuse() {
        throw new ToolBusinessError("NOPE", "Refused");
    }

    @ServerTool(name = "demo.watch", title = "Watch", description = "Runs until cancelled.", effect = EffectSafety.READ_ONLY, async = true,
            cancelIfAbandoned = true)
    public Map<String, Object> watch(ToolContext context) {
        while (!context.isCancelled()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                break;
            }
        }
        return Map.of("watched", true);
    }

    @ServerTool(name = "demo.inline", title = "Inline", description = "Asks for input in a sync call.", effect = EffectSafety.READ_ONLY)
    public Map<String, Object> inline(ToolContext context) {
        return Map.of("v", context.requestInput(InputRequest.of("?", Map.of("type", "string"), String.class)));
    }
}
