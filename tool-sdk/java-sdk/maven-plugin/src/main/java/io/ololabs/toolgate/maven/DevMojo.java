// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.maven;

import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/** {@code toolgate:dev}: serves the tools over stdio in development mode, with {@code toolgate.dev.json} fixtures. */
public class DevMojo extends AbstractToolGateMojo {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        requireGeneration();
        int exit = fork(runtimeClasspath(), RUNTIME_MAIN, List.of(), Map.of("TOOLGATE_MODE", "development"), true);
        if (exit != 0) throw new MojoFailureException("the development server exited with " + exit);
    }
}
