// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The Tool Host's broker, over the Unix socket it made for this invocation (platform-requirements.md §11.8): {@code POST /v1/call}
 * sends a route call, in the request shape of {@link TestExchanges}, and {@code POST /v1/secrets} redeems a secret
 * handle. Every request carries the invocation's capability; the broker decides everything else.
 */
final class BrokerClient implements TestExchanges {
    private static final int MAX_REPLY = 8 * 1024 * 1024;

    private final ObjectMapper json;
    private final Path socket;
    private final String capability;

    BrokerClient(ObjectMapper json, Path socket, String capability) {
        this.json = json;
        this.socket = socket;
        this.capability = capability;
    }

    @Override
    public JsonNode exchange(ObjectNode request) {
        return post("/v1/call", request);
    }

    /** The value of a secret handle, or null when the broker refuses it. */
    JsonNode redeem(String handle) {
        JsonNode reply = post("/v1/secrets", json.createObjectNode().put("handle", handle));
        return reply.hasNonNull("refused") ? null : reply.get("value");
    }

    private JsonNode post(String path, JsonNode body) {
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String head = "POST " + path + " HTTP/1.1\r\nHost: broker\r\nToolgate-Capability: " + capability
                + "\r\nContent-Type: application/json\r\nContent-Length: " + payload.length + "\r\nConnection: close\r\n\r\n";
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            ByteBuffer out = ByteBuffer.allocate(head.length() + payload.length);
            out.put(head.getBytes(StandardCharsets.US_ASCII)).put(payload).flip();
            while (out.hasRemaining()) channel.write(out);
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            var in = Channels.newInputStream(channel);
            for (int n; (n = in.read(chunk)) > 0; ) {
                reply.write(chunk, 0, n);
                if (reply.size() > MAX_REPLY) throw new IOException("the broker's reply is larger than " + MAX_REPLY + " bytes");
            }
            return parse(reply.toByteArray());
        } catch (IOException e) {
            // The broker is part of the host; losing it means the call's outcome is unknown to the tool.
            ObjectNode failure = json.createObjectNode();
            failure.put("refused", "the Tool Host broker is unavailable: " + e.getMessage());
            return failure;
        }
    }

    private JsonNode parse(byte[] reply) throws IOException {
        String text = new String(reply, StandardCharsets.ISO_8859_1);
        int end = text.indexOf("\r\n\r\n");
        if (!text.startsWith("HTTP/1.1 ") || end < 0) throw new IOException("the broker's reply is not HTTP/1.1");
        int status = Integer.parseInt(text.substring(9, 12));
        byte[] body = java.util.Arrays.copyOfRange(reply, end + 4, reply.length);
        JsonNode node = body.length == 0 ? json.createObjectNode() : json.readTree(body);
        if (status != 200 && !node.hasNonNull("refused")) {
            return json.createObjectNode().put("refused", "the Tool Host broker answered " + status);
        }
        return node;
    }
}
