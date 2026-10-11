// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/**
 * {@code toolgate:package}, in {@code package} after the jar: stages the package under {@code target/toolgate/staging}
 * and packs it with the {@code toolgate} CLI into {@code target/toolgate/<artifactId>-<version>.tgpkg}.
 */
public class PackageMojo extends AbstractToolGateMojo {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        requireGeneration();
        File jar = project.getArtifact().getFile();
        if (jar == null || !jar.isFile()) {
            throw new MojoFailureException("the project jar is not built; run toolgate:package in the package phase, after jar");
        }
        File out = new File(project.getBuild().getDirectory(), "toolgate");
        List<String> args = new ArrayList<>(List.of("stage", "--jar", jar.getPath(), "--version", project.getVersion(),
                "--out", new File(out, "staging").getPath()));
        for (Artifact a : project.getArtifacts()) {
            String scope = a.getScope();
            if (a.getFile() != null && (Artifact.SCOPE_COMPILE.equals(scope) || Artifact.SCOPE_RUNTIME.equals(scope))) {
                args.add("--lib");
                args.add(a.getFile().getPath());
            }
        }
        args.add("--pack");
        args.add(new File(out, project.getArtifactId() + "-" + project.getVersion() + ".tgpkg").getPath());
        int exit = fork(buildClasspath(), BUILD_MAIN, args, Map.of(), false);
        if (exit != 0) throw new MojoFailureException("toolgate package failed; see the lines above");
    }
}
