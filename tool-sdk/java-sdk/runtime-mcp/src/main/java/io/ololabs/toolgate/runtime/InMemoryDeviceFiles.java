// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import io.ololabs.toolgate.sdk.context.DeviceFiles;
import io.ololabs.toolgate.sdk.errors.PlatformError;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * An in-memory device files area, empty at start, for a ClientTool run outside a device: {@code ToolHarness} tests,
 * {@code toolgate check} and {@code toolgate dev} (ADR 027). It applies the device's naming rules —
 * relative, lowercase portable names, no traversal or hidden names, an allowed extension for files — and its size
 * limit, so a tool that passes here asks the device for nothing it would refuse.
 */
public final class InMemoryDeviceFiles implements DeviceFiles {
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._ -]{0,127}");
    private final TreeMap<String, String> files = new TreeMap<>();
    private final TreeSet<String> directories = new TreeSet<>();
    private final List<String> extensions;
    private final int maxFileBytes;

    public InMemoryDeviceFiles() { this(List.of("txt", "md", "json", "csv"), 65536); }
    public InMemoryDeviceFiles(List<String> extensions, int maxFileBytes) { this.extensions = List.copyOf(extensions); this.maxFileBytes = maxFileBytes; }

    private static PlatformError refused(String message) { return new PlatformError(PlatformError.Code.FILES_REFUSED, message); }
    private String checked(String path, boolean file) {
        if (path == null || path.isEmpty() || path.length() > 1024) throw refused("Invalid path");
        String[] parts = path.split("/", -1);
        if (parts.length > 16) throw refused("Invalid path");
        for (String part : parts) if (!NAME.matcher(part).matches() || part.endsWith(".") || part.endsWith(" ") || part.equals("..")) throw refused("Invalid path");
        if (file) {
            int dot = path.lastIndexOf('.');
            if (dot < 0 || !extensions.contains(path.substring(dot + 1))) throw refused("This file type is not allowed");
        }
        String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : null;
        if (parent != null && !directories.contains(parent)) throw refused("No such directory");
        return path;
    }
    private void bounded(String text) { if (text.getBytes(StandardCharsets.UTF_8).length > maxFileBytes) throw refused("The file is too large"); }

    /** Seeds a file, as if it already existed on the device. */
    public InMemoryDeviceFiles file(String path, String text) { files.put(checked(path, true), text); return this; }
    public String content(String path) { return files.get(path); }

    @Override public synchronized List<String> list() { var all = new TreeSet<String>(directories); all.addAll(files.keySet()); return new ArrayList<>(all); }
    @Override public synchronized String readText(String path) { var text = files.get(checked(path, true)); if (text == null) throw refused("No such file"); return text; }
    @Override public synchronized Info info(String path) { return new Info(path, readText(path).getBytes(StandardCharsets.UTF_8).length); }
    @Override public synchronized void writeText(String path, String text) { bounded(text); files.put(checked(path, true), text); }
    @Override public synchronized void appendText(String path, String text) { String next = files.getOrDefault(checked(path, true), "") + text; bounded(next); files.put(path, next); }
    @Override public synchronized void mkdir(String path) { checked(path, false); if (!directories.add(path) || files.containsKey(path)) throw refused("Already exists"); }
    @Override public synchronized void rename(String path, String destination) {
        String text = readText(path); checked(destination, true);
        if (files.containsKey(destination)) throw refused("The destination exists");
        files.remove(path); files.put(destination, text);
    }
}
