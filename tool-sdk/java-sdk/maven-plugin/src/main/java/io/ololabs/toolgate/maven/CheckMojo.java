// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/** {@code toolgate:check}, in {@code test}, so failing examples stop packaging: every {@code @Example} passes in test mode and the dev file is untracked. */
public class CheckMojo extends AbstractToolGateMojo {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("toolgate: check skipped");
            return;
        }
        requireGeneration();
        List<String> cp = new ArrayList<>(runtimeClasspath());
        cp.addAll(buildClasspath());
        int exit = fork(cp, BUILD_MAIN, List.of("check", project.getBasedir().getAbsolutePath()), Map.of(), false);
        if (exit != 0) throw new MojoFailureException("toolgate check failed; see the lines above");
    }
}
