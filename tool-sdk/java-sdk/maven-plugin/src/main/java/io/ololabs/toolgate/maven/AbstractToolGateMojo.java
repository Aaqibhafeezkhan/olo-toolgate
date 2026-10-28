// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

/**
 * What the goals share: the project, the plugin's own class path (toolgate-sdk-build and what it needs), the required
 * {@code descriptorGeneration}, and a forked JVM, so tool code never runs inside Maven. Fields are set by Maven from
 * META-INF/maven/plugin.xml.
 */
abstract class AbstractToolGateMojo extends AbstractMojo {
    static final String RUNTIME_MAIN = "io.ololabs.toolgate.runtime.Main";
    static final String BUILD_MAIN = "io.ololabs.toolgate.sdkbuild.Main";

    MavenProject project;
    List<Artifact> pluginArtifacts;
    Integer descriptorGeneration;
    boolean skip;

    /** The project's runtime class path: its classes and its compile and runtime dependencies. */
    List<String> runtimeClasspath() throws MojoExecutionException {
        try {
            return project.getRuntimeClasspathElements();
        } catch (org.apache.maven.artifact.DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("the runtime class path is not resolved", e);
        }
    }

    /** toolgate-sdk-build and its dependencies, from the plugin's class path. */
    List<String> buildClasspath() {
        List<String> out = new ArrayList<>();
        for (Artifact a : pluginArtifacts) {
            if (a.getFile() != null && !a.getArtifactId().equals("toolgate-maven-plugin")) out.add(a.getFile().getPath());
        }
        return out;
    }

    void requireGeneration() throws MojoFailureException {
        if (descriptorGeneration == null) {
            throw new MojoFailureException("set <descriptorGeneration> in the toolgate-maven-plugin configuration; it is required and has no default");
        }
    }

    /** Runs {@code mainClass} in a new JVM with {@code classpath}; returns its exit code. */
    int fork(List<String> classpath, String mainClass, List<String> args, Map<String, String> env, boolean interactive)
            throws MojoExecutionException {
        Set<String> cp = new LinkedHashSet<>(classpath);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, cp));
        command.add(mainClass);
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).directory(project.getBasedir());
        pb.environment().putAll(env);
        if (interactive) pb.inheritIO();
        else pb.redirectInput(ProcessBuilder.Redirect.INHERIT).redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
            return pb.start().waitFor();
        } catch (IOException e) {
            throw new MojoExecutionException("cannot start " + command.get(0), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("interrupted", e);
        }
    }
}
