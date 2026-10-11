// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.notes;

import io.ololabs.toolgate.sdk.annotations.Destination;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.HttpMethod;
import io.ololabs.toolgate.sdk.annotations.HttpRoute;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.context.ToolContext;
import java.util.Map;

/** The sample the plugin end-to-end run builds with Gradle and with Maven. */
@ToolPackage(id = "acme.notes", name = "Notes", publisher = "Acme")
@Destination(id = "notes-api", hostSetting = "NotesApiHost")
@HttpRoute(id = "notes.get", destination = "notes-api", method = HttpMethod.GET, path = "/notes/{id:[0-9]+}")
@Global(name = "Greeting", type = ValueType.STRING, defaultValue = "hello")
public class NoteTools {
    public record Note(String id, String text) {}

    @ServerTool(name = "notes.greet", title = "Greet", description = "Greets someone.", effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"name\":\"Ada\"}", expectedOutput = "{\"text\":\"hello Ada\"}")
    public Map<String, Object> greet(@Param(description = "Who") String name, ToolContext context) {
        return Map.of("text", NoteToolsVars.global(context).greeting() + " " + name);
    }

    @ServerTool(name = "notes.get", title = "Note", description = "Reads a note.", effect = EffectSafety.READ_ONLY, routes = "notes.get")
    @Example(arguments = "{\"id\":\"1\"}", expectedOutput = "{\"id\":\"1\",\"text\":\"first\"}", exchanges = "exchanges/note-1.json")
    public Note get(@Param(description = "Note id") String id, ToolContext context) {
        return context.http().get("notes-api", "/notes/{id}").pathVar("id", id).send(Note.class);
    }
}
