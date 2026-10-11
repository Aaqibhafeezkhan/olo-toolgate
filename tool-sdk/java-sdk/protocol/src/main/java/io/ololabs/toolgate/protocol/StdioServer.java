// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Newline-delimited JSON-RPC over stdio. Requests run concurrently; each response is written as one line. The
 * connection is locked to the era of its first accepted message.
 */
public final class StdioServer {
    /** The longest line accepted: the 1 MiB input ceiling plus the envelope. */
    public static final int MAX_LINE = 2 * 1024 * 1024;

    private final ProtocolCore core;
    private final ObjectMapper json = new ObjectMapper();

    public StdioServer(ProtocolCore core) {
        this.core = core;
    }

    /** Serves until the input ends, then waits for in-flight requests. */
    public void serve(InputStream in, OutputStream out) throws IOException {
        ProtocolCore.Connection connection = new ProtocolCore.Connection("stdio");
        ExecutorService workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "toolgate-stdio");
            t.setDaemon(true);
            return t;
        });
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = readLine(reader, out)) != null) {
                if (line.isBlank()) continue;
                JsonNode message;
                try {
                    message = json.readTree(line);
                } catch (JsonProcessingException e) {
                    write(out, ProtocolCore.error(null, new ProtocolError(ProtocolError.PARSE_ERROR, "Parse error")));
                    continue;
                }
                workers.execute(() -> {
                    JsonNode response = core.handle(message, connection);
                    if (response != null) write(out, response);
                });
            }
        } finally {
            workers.shutdown();
            try {
                workers.awaitTermination(70, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String readLine(BufferedReader reader, OutputStream out) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        boolean tooLong = false;
        while ((c = reader.read()) != -1 && c != '\n') {
            if (line.length() < MAX_LINE) line.append((char) c);
            else tooLong = true;
        }
        if (c == -1 && line.length() == 0) return null;
        if (tooLong) {
            write(out, ProtocolCore.error(null, new ProtocolError(ProtocolError.INVALID_REQUEST, "Request too large")));
            return "";
        }
        return line.toString();
    }

    private void write(OutputStream out, JsonNode response) {
        try {
            byte[] bytes = json.writeValueAsBytes(response);
            synchronized (out) {
                out.write(bytes);
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
