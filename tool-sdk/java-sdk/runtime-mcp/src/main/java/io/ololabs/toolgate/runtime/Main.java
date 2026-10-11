// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import java.io.IOException;
import java.nio.file.Path;

/** {@code java -cp <tool and SDK jars> io.ololabs.toolgate.runtime.Main}: serves the tools on the class path over stdio. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        StandaloneRuntime runtime;
        try {
            runtime = StandaloneRuntime.start(Thread.currentThread().getContextClassLoader(), System.getenv(), Path.of(""), System.err);
        } catch (StartupRefused e) {
            System.err.println("toolgate: refusing to start: " + e.getMessage());
            System.exit(78);
            return;
        }
        if (runtime.httpAddress() != null) {
            runtime.serveHttp(runtime.httpAddress());
            Thread.currentThread().join();
        } else {
            runtime.serveStdio(System.in, System.out);
        }
    }
}
