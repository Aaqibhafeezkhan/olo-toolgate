// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.sdk.context.DeviceFiles;
import io.ololabs.toolgate.sdk.errors.PlatformError;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The executor's broker over tool protocol v2, the one protocol for every target (ADR 027): each operation is one
 * {@code BROKER_REQUEST} frame on stdout, answered by one {@code BROKER_RESPONSE} line on stdin. A Tool Host answers
 * route calls and secret redemption for what the permit grants; a device answers the files primitives for what the
 * signed registration grants; each refuses the rest. This side only frames requests: the executor decides everything.
 */
final class StdioBroker implements TestExchanges, DeviceFiles {
    static final int MAX_CALLS = 1024;
    private static final int MAX_LINE = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final InputStream in; private final Consumer<ObjectNode> out;
    private int calls;

    StdioBroker(InputStream in, Consumer<ObjectNode> out) { this.in = in; this.out = out; }

    /** Thrown when the executor refuses or the channel fails; carries the executor's message. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Refused(String message) { super(message); }
    }

    /** One brokered operation; one request is outstanding at a time. */
    synchronized JsonNode call(String operation, ObjectNode request) {
        if (++calls > MAX_CALLS) throw new Refused("At most " + MAX_CALLS + " brokered operations per invocation");
        String id = "broker-" + calls;
        ObjectNode frame = JSON.createObjectNode().put("kind", "BROKER_REQUEST").put("requestId", id).put("operation", operation);
        frame.set("request", request);
        out.accept(frame);
        JsonNode response;
        try { response = JSON.readTree(line()); } catch (IOException e) { throw new Refused("The executor's broker channel failed"); }
        if (response == null || !"BROKER_RESPONSE".equals(response.path("kind").asText()) || !id.equals(response.path("requestId").asText()))
            throw new Refused("The executor's broker answered out of order");
        if (response.has("error")) throw new Refused(response.path("error").path("message").asText("The executor refused the operation"));
        return response.path("result").isObject() ? response.get("result") : JSON.createObjectNode();
    }
    private byte[] line() throws IOException {
        var buffer = new ByteArrayOutputStream();
        for (int b = in.read(); b != '\n'; b = in.read()) {
            if (b == -1) throw new IOException("closed");
            if (buffer.size() >= MAX_LINE) throw new IOException("too long");
            buffer.write(b);
        }
        return buffer.toByteArray();
    }

    /** A route call in the recorded-exchange shape; a refusal comes back as {@code {refused}}, as the route client expects. */
    @Override
    public JsonNode exchange(ObjectNode request) {
        try { return call("ROUTE_CALL", request); }
        catch (Refused refused) { return JSON.createObjectNode().put("refused", refused.getMessage()); }
    }

    /** The value of a secret handle, or null when the executor refuses it. */
    JsonNode redeem(String handle) {
        try { return call("SECRET_REDEEM", JSON.createObjectNode().put("handle", handle)).get("value"); }
        catch (Refused refused) { return null; }
    }

    private JsonNode files(String operation, String path, String destination, String text) {
        ObjectNode request = JSON.createObjectNode();
        if (path != null) request.put("path", path);
        if (destination != null) request.put("destination", destination);
        if (text != null) request.put("text", text);
        try { return call(operation, request); }
        catch (Refused refused) { throw new PlatformError(PlatformError.Code.FILES_REFUSED, refused.getMessage()); }
    }
    public List<String> list() { var paths = new ArrayList<String>(); files("FILES_LIST", null, null, null).path("paths").forEach(p -> paths.add(p.asText())); return List.copyOf(paths); }
    public String readText(String path) { return files("FILES_READ", path, null, null).path("text").asText(); }
    public Info info(String path) { var r = files("FILES_INFO", path, null, null); return new Info(r.path("path").asText(), r.path("bytes").asLong()); }
    public void writeText(String path, String text) { files("FILES_WRITE", path, null, text); }
    public void appendText(String path, String text) { files("FILES_APPEND", path, null, text); }
    public void mkdir(String path) { files("FILES_MKDIR", path, null, null); }
    public void rename(String path, String destination) { files("FILES_RENAME", path, destination, null); }

    /** The same area without write primitives, for a tool that declared only read access. */
    static DeviceFiles readOnly(DeviceFiles files) {
        return new DeviceFiles() {
            private PlatformError refused() { return new PlatformError(PlatformError.Code.FILES_REFUSED, "This tool declared read-only device file access"); }
            public List<String> list() { return files.list(); }
            public String readText(String path) { return files.readText(path); }
            public Info info(String path) { return files.info(path); }
            public void writeText(String path, String text) { throw refused(); }
            public void appendText(String path, String text) { throw refused(); }
            public void mkdir(String path) { throw refused(); }
            public void rename(String path, String destination) { throw refused(); }
        };
    }
}
