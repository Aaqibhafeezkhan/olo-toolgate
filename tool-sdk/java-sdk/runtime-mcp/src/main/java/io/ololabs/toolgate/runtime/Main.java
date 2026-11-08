// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code java -cp <tool and SDK jars> io.ololabs.toolgate.runtime.Main}: serves the tools on the class path over stdio,
 * or, with {@code TOOLGATE_PROTOCOL=2} from a Tool Host, runs one governed invocation over tool protocol v2; with
 * {@code TOOLGATE_EXECUTION_MODE=TRIGGER} as well, it runs one trigger instance under an executor's supervisor.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        if ("2".equals(System.getenv("TOOLGATE_PROTOCOL"))) {
            String mode = System.getenv("TOOLGATE_EXECUTION_MODE");
            if ("TRIGGER".equals(mode)) {
                System.exit(TriggerRuntime.run(Thread.currentThread().getContextClassLoader(), System.getenv(), System.in, System.out, System.err));
                return;
            }
            if (mode != null) {
                System.err.println("toolgate: TOOLGATE_EXECUTION_MODE must be TRIGGER or unset, not " + mode);
                System.exit(78);
                return;
            }
            System.exit(Governed.run(Thread.currentThread().getContextClassLoader(), System.getenv(), System.in, System.out, System.err));
            return;
        }
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
