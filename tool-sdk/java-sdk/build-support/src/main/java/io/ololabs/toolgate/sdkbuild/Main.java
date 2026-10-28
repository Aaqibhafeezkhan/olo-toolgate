// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdkbuild;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The entry point the Gradle and Maven plugins fork, with the tools' runtime class path and this library on the class
 * path, so a build never loads tool code into the build tool's own JVM.
 *
 * <pre>
 * check &lt;project dir&gt;
 * stage --jar &lt;app.jar&gt; --version &lt;semver&gt; --out &lt;dir&gt; [--lib &lt;jar&gt;]... [--pack &lt;file.tgpkg&gt;]
 * </pre>
 *
 * Exit codes: 0 success, 1 a check or the packer failed, 2 usage.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0) return usage();
        switch (args[0]) {
            case "check" -> {
                if (args.length != 2) return usage();
                Checks.Report report = Checks.run(Thread.currentThread().getContextClassLoader(), Path.of(args[1]));
                report.ran().forEach(line -> System.out.println("toolgate: " + line));
                if (report.passed()) {
                    System.out.println("toolgate: check passed (" + report.ran().size() + " examples)");
                    return 0;
                }
                report.problems().forEach(p -> System.err.println("toolgate: FAILED " + p));
                return 1;
            }
            case "stage" -> {
                Path jar = null;
                Path out = null;
                Path pack = null;
                String version = null;
                List<Path> libs = new ArrayList<>();
                for (int i = 1; i < args.length; i += 2) {
                    if (i + 1 >= args.length) return usage();
                    String v = args[i + 1];
                    switch (args[i]) {
                        case "--jar" -> jar = Path.of(v);
                        case "--version" -> version = v;
                        case "--out" -> out = Path.of(v);
                        case "--lib" -> libs.add(Path.of(v));
                        case "--pack" -> pack = Path.of(v);
                        default -> {
                            return usage();
                        }
                    }
                }
                if (jar == null || out == null || version == null) return usage();
                try {
                    Staging.stage(jar, libs, version, out);
                    System.out.println("toolgate: staged " + out);
                    if (pack != null) System.out.print(Packer.pack(out, pack, System.getenv()));
                } catch (IllegalArgumentException | IllegalStateException e) {
                    System.err.println("toolgate: " + e.getMessage());
                    return 1;
                }
                return 0;
            }
            default -> {
                return usage();
            }
        }
    }

    private static int usage() {
        System.err.println("usage: check <project dir> | stage --jar <app.jar> --version <semver> --out <dir> [--lib <jar>]... [--pack <file.tgpkg>]");
        return 2;
    }
}
