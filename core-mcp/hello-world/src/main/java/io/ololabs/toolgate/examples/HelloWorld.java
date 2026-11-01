// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.examples;

import io.ololabs.toolgate.sdk.annotations.ClientTool;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Training;
import io.ololabs.toolgate.sdk.annotations.TrainingPrompt;
import io.ololabs.toolgate.sdk.annotations.TrainingValues;
import io.ololabs.toolgate.sdk.context.ToolContext;

/** Two declarations, one implementation; no endpoint or host special-case dispatcher. */
@ToolPackage(id = "ololabs.hello-world", name = "Hello World", publisher = "ololabs")
public final class HelloWorld {
    public record Greeting(@Param(name = "message", description = "The greeting") String message) {}

    @ClientTool(name = "client_greet", title = "Hello World on a device", description = "Greet someone on your selected client device", effect = EffectSafety.READ_ONLY, stateless = true)
    @Example(arguments = "{}", expectedOutput = "{\"message\":\"Hello World\"}")
    @Training(
            phrasings = {
                "Say hello to {text} on my device",
                "Greet {text} from my laptop",
                "Show a greeting for {text} on this client",
                "Can you say hi to {text} on my computer?"
            },
            defaultPhrasings = {"Say hello on my device", "Show the hello world greeting on this client"},
            prompts = @TrainingPrompt(prompt = "Greet Ada on my laptop", arguments = "{\"text\":\"Ada\"}"))
    public Greeting client(ToolContext context,
            @Param(name = "text", description = "Who to greet", defaultValue = "World", maxLength = 128)
            @TrainingValues(examples = {"Ada", "the team", "Priya", "everyone in the room", "Dr. Okafor"}) String text) {
        return greet(context, text);
    }

    @ServerTool(name = "server_greet", title = "Hello World", description = "Greet someone on a Tool Host or supported client device", effect = EffectSafety.READ_ONLY, stateless = true)
    @Example(arguments = "{}", expectedOutput = "{\"message\":\"Hello World\"}")
    @Training(
            phrasings = {
                "Greet {text} from the server",
                "Have the tool host say hello to {text}",
                "Send a hello to {text} using the server greeting tool"
            },
            defaultPhrasings = "Run the server hello world greeting")
    public Greeting server(ToolContext context,
            @Param(name = "text", description = "Who to greet", defaultValue = "World", maxLength = 128)
            @TrainingValues(examples = {"Grace", "the ops team", "Lin", "new users"}) String text) {
        return greet(context, text);
    }

    private Greeting greet(ToolContext context, String text) {
        context.log().info("Producing greeting");
        var response = new Greeting("Hello " + text);
        context.progress(1, "Greeting complete");
        return response;
    }
}

