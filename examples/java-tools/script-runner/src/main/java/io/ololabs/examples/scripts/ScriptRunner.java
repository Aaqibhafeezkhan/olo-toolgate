// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.scripts;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Reconciliation;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import io.ololabs.toolgate.sdk.errors.ToolConfigurationError;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs a script an administrator placed in an allowlisted directory and returns the last lines of its console output.
 *
 * <p>Safe by default: the caller names a script, never a command line. The script must sit directly in
 * {@code ScriptsDir} (no sub-directories, no {@code ..}, no symbolic link out of it), have an allowed extension, and,
 * when {@code AllowedScripts} is not {@code *}, appear in that list. Arguments are passed as separate argv entries and
 * restricted to characters no shell treats specially, so a caller cannot chain commands even through {@code cmd.exe}.
 * The script runs with a minimal environment, a deadline, and its whole process tree is killed when it overruns.
 */
@ToolPackage(id = "examples.scripts", name = "Script runner", publisher = "ToolGate Examples",
        description = "Runs allowlisted batch, shell and PowerShell scripts and returns their last lines of output.")
@Global(name = "ScriptsDir", type = ValueType.STRING, defaultValue = "scripts",
        description = "Directory holding the scripts callers may run. Use an absolute path under a Tool Host.")
@Global(name = "AllowedScripts", type = ValueType.STRING, defaultValue = "*",
        description = "Comma-separated script file names callers may run, or * for every script directly in ScriptsDir.")
@Global(name = "MaxRunSeconds", type = ValueType.INT, defaultValue = "50", min = "1", max = "55",
        description = "Upper bound on a run, whatever timeoutSeconds the caller asks for.")
@Global(name = "ShellPath", type = ValueType.STRING, defaultValue = "/bin/sh",
        description = "Interpreter for .sh scripts.")
@Global(name = "CmdPath", type = ValueType.STRING, defaultValue = "C:\\Windows\\System32\\cmd.exe",
        description = "Interpreter for .bat and .cmd scripts.")
@Global(name = "PowerShellPath", type = ValueType.STRING,
        defaultValue = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
        description = "Interpreter for .ps1 scripts. Set it to pwsh's path to use PowerShell 7.")
@Global(name = "ScriptPathVariable", type = ValueType.STRING, defaultValue = "default",
        description = "PATH the script sees, or 'default' for the operating system's standard directories.")
public class ScriptRunner {
    static final Set<String> EXTENSIONS = Set.of("sh", "bat", "cmd", "ps1");
    static final Pattern SCRIPT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    /** Characters no shell, cmd.exe included, treats as syntax. */
    static final Pattern SAFE_ARGUMENT = Pattern.compile("[A-Za-z0-9 ._:/\\\\=@,+-]{0,256}");
    static final int MAX_LINE_CHARS = 2000;
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    public record ScriptRun(
            @Param(description = "The script that ran") String script,
            @Param(description = "Process exit code; -1 when the run was killed at its deadline") int exitCode,
            @Param(description = "True when the run hit its deadline and was killed") boolean timedOut,
            @Param(description = "How long the run took, in milliseconds") long durationMs,
            @Param(description = "Lines the script printed to stdout and stderr together") long totalLines,
            @Param(description = "The last lines of output, oldest first") List<String> lastLines) {}

    @ServerTool(name = "run", title = "Run a script",
            description = "Runs one of the administrator's allowlisted scripts (.bat, .cmd, .ps1 or .sh) and returns its"
                    + " exit code and the last lines of its console output.",
            useWhen = "Use it to run a known maintenance or report script. Use scripts.list first to see what exists.",
            effect = EffectSafety.NON_IDEMPOTENT, maxDurationMs = 60_000, maxConcurrency = 4,
            errors = {
                @ErrorDecl(code = "SCRIPT_NOT_FOUND", description = "No allowlisted script has that name."),
                @ErrorDecl(code = "SCRIPT_FAILED_TO_START", description = "The interpreter could not be started.")}, reconciliation = Reconciliation.MANUAL)
    @Example(arguments = "{\"script\":\"../../etc/passwd\"}", expectedError = "INVALID_ARGUMENT")
    @Example(arguments = "{\"script\":\"cleanup.sh\",\"arguments\":[\"a && rm -rf /\"]}", expectedError = "INVALID_ARGUMENT")
    public ScriptRun run(
            @Param(description = "File name of the script in the scripts directory, such as nightly-report.bat",
                    maxLength = 128) String script,
            @Param(description = "Arguments passed to the script, each as one argument. Letters, digits, spaces and"
                    + " . _ : / \\ = @ , + - only.", required = false) List<String> arguments,
            @Param(description = "How many trailing lines of output to return", defaultValue = "20", min = "1",
                    max = "500") int lines,
            @Param(description = "Seconds before the run is killed", defaultValue = "30", min = "1", max = "55")
            int timeoutSeconds,
            ToolContext context) {
        if (!SCRIPT_NAME.matcher(script).matches()) {
            throw new ToolInputError("script must be a plain file name such as report.bat, without a directory");
        }
        List<String> args = arguments == null ? List.of() : arguments;
        if (args.size() > 32) throw new ToolInputError("at most 32 arguments");
        for (String a : args) {
            if (a == null || !SAFE_ARGUMENT.matcher(a).matches()) {
                throw new ToolInputError("arguments may hold only letters, digits, spaces and . _ : / \\ = @ , + -");
            }
        }
        String extension = extension(script);
        if (!EXTENSIONS.contains(extension)) {
            throw new ToolInputError("script must end in .sh, .bat, .cmd or .ps1");
        }
        Path file = allowedScript(context, script);
        List<String> command = new ArrayList<>(interpreter(context, extension, file));
        command.addAll(args);

        int deadline = Math.min(timeoutSeconds, context.global().getIntValue("MaxRunSeconds"));
        ProcessBuilder builder = new ProcessBuilder(command).directory(file.getParent().toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(minimalEnvironment(context));
        long started = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ToolBusinessError("SCRIPT_FAILED_TO_START", "The interpreter for ." + extension + " scripts could not be started.");
        }
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // Nothing to send; the script reads no input.
        }
        Tail tail = new Tail(lines);
        Thread reader = new Thread(() -> tail.consume(process), "script-output");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(deadline, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        context.log().info("ran " + script + " in " + durationMs + " ms");
        return new ScriptRun(script, finished ? process.exitValue() : -1, !finished, durationMs, tail.total(), tail.lines());
    }

    public record ScriptList(@Param(description = "Scripts callers may run") List<String> scripts) {}

    @ServerTool(name = "list", title = "List scripts",
            description = "Lists the scripts callers may run with scripts.run.", effect = EffectSafety.READ_ONLY)
    public ScriptList list(ToolContext context) {
        Path dir = scriptsDir(context);
        Set<String> allowed = allowedNames(context);
        try (var files = Files.list(dir)) {
            return new ScriptList(files
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.getFileName().toString())
                    .filter(n -> SCRIPT_NAME.matcher(n).matches() && EXTENSIONS.contains(extension(n)))
                    .filter(n -> allowed == null || allowed.contains(n))
                    .sorted()
                    .toList());
        } catch (IOException e) {
            throw new ToolConfigurationError("ScriptsDir cannot be read");
        }
    }

    // ---- safety checks ----

    static Path scriptsDir(ToolContext context) {
        Path dir = Path.of(context.global().getStringValue("ScriptsDir"));
        if (!Files.isDirectory(dir)) throw new ToolConfigurationError("ScriptsDir is not a directory");
        try {
            return dir.toRealPath();
        } catch (IOException e) {
            throw new ToolConfigurationError("ScriptsDir cannot be resolved");
        }
    }

    /** Null means every script in the directory. */
    static Set<String> allowedNames(ToolContext context) {
        String list = context.global().getStringValue("AllowedScripts").trim();
        if (list.equals("*")) return null;
        return Set.copyOf(Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    static Path allowedScript(ToolContext context, String script) {
        Set<String> allowed = allowedNames(context);
        if (allowed != null && !allowed.contains(script)) {
            throw new ToolBusinessError("SCRIPT_NOT_FOUND", "No allowlisted script is called " + script + ".");
        }
        Path dir = scriptsDir(context);
        Path file = dir.resolve(script);
        try {
            // Resolving links proves the file really lives in the scripts directory.
            if (!Files.isRegularFile(file) || !file.toRealPath().getParent().equals(dir)) {
                throw new ToolBusinessError("SCRIPT_NOT_FOUND", "No allowlisted script is called " + script + ".");
            }
            return file.toRealPath();
        } catch (IOException e) {
            throw new ToolBusinessError("SCRIPT_NOT_FOUND", "No allowlisted script is called " + script + ".");
        }
    }

    static List<String> interpreter(ToolContext context, String extension, Path file) {
        String path = file.toString();
        return switch (extension) {
            case "sh" -> List.of(context.global().getStringValue("ShellPath"), path);
            // /d skips AutoRun commands from the registry; /c runs the script and exits.
            case "bat", "cmd" -> List.of(context.global().getStringValue("CmdPath"), "/d", "/c", path);
            case "ps1" -> List.of(context.global().getStringValue("PowerShellPath"), "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", path);
            default -> throw new ToolInputError("unsupported script type");
        };
    }

    static java.util.Map<String, String> minimalEnvironment(ToolContext context) {
        String path = context.global().getStringValue("ScriptPathVariable");
        java.util.Map<String, String> env = new java.util.HashMap<>();
        if (WINDOWS) {
            env.put("Path", path.equals("default") ? "C:\\Windows\\System32;C:\\Windows;C:\\Windows\\System32\\WindowsPowerShell\\v1.0" : path);
            env.put("SystemRoot", "C:\\Windows");
            env.put("ComSpec", context.global().getStringValue("CmdPath"));
            env.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        } else {
            env.put("PATH", path.equals("default") ? "/usr/local/bin:/usr/bin:/bin" : path);
            env.put("LANG", "C.UTF-8");
        }
        return env;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Keeps only the last {@code n} lines, so a chatty script cannot exhaust memory. */
    static final class Tail {
        private final int keep;
        private final ArrayDeque<String> lines = new ArrayDeque<>();
        private long total;

        Tail(int keep) {
            this.keep = keep;
        }

        void consume(Process process) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) add(line);
            } catch (IOException e) {
                // The process was killed at its deadline; keep what was read.
            }
        }

        synchronized void add(String line) {
            total++;
            lines.addLast(line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + "…" : line);
            if (lines.size() > keep) lines.removeFirst();
        }

        synchronized long total() {
            return total;
        }

        synchronized List<String> lines() {
            return List.copyOf(lines);
        }
    }
}
