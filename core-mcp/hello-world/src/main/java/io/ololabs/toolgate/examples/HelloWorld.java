// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.examples;

import io.ololabs.toolgate.sdk.annotations.ClientTool;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.ModelValidation;
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
                "Can you say hi to {text} on my computer?",
                "Say hi to {text} on my laptop",
                "Pop up a hello for {text} on this device",
                "Greet {text} on my machine",
                "Show a hello for {text} on my screen"
            },
            defaultPhrasings = {"Say hello on my device", "Show the hello world greeting on this client",
                "Show the default hello on my device"
            },
            prompts = {
                @TrainingPrompt(prompt = "Greet Ada on my laptop", arguments = "{\"text\":\"Ada\"}"),
                @TrainingPrompt(prompt = "say hi to Elena on my laptop", arguments = "{\"text\":\"Elena\"}"),
                @TrainingPrompt(prompt = "Pop up a hello for the design team on this device", arguments = "{\"text\":\"the design team\"}"),
                @TrainingPrompt(prompt = "Show the standard hello on my computer", arguments = "{}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "wave hi to Tomasz on this pc pls", arguments = "{\"text\":\"Tomasz\"}"),
                @TrainingPrompt(prompt = "Put a welcome message for the night shift on my screen", arguments = "{\"text\":\"the night shift\"}"),
                @TrainingPrompt(prompt = "Show me the plain hello on my own machine", arguments = "{}"),
                @TrainingPrompt(prompt = "greet Priya on my pc", arguments = "{\"text\":\"Priya\"}"),
                @TrainingPrompt(prompt = "hello popup on my screen please", arguments = "{}")
            },
            negatives = {"How do I write hello world in Rust?", "Translate hello into French"})
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
                "Send a hello to {text} using the server greeting tool",
                "Use the server greeting to welcome {text}",
                "Have the tool host greet {text}",
                "Server hello for {text}"
            },
            defaultPhrasings = {
                "Run the server hello world greeting",
                "Run hello world on the tool host",
                "Server-side hello"
            },
            prompts = {
                @TrainingPrompt(prompt = "Use the server greeting for Omar", arguments = "{\"text\":\"Omar\"}"),
                @TrainingPrompt(prompt = "Run the server hello world with no name", arguments = "{}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "Ask the tool host to welcome the finance team", arguments = "{\"text\":\"the finance team\"}"),
                @TrainingPrompt(prompt = "server-side hello for Kenji please", arguments = "{\"text\":\"Kenji\"}"),
                @TrainingPrompt(prompt = "tool host: say hello to the support team", arguments = "{\"text\":\"the support team\"}")
            },
            negatives = "Is the tool host up right now?")
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

