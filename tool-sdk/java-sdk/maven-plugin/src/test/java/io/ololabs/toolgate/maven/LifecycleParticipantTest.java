// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

/** The build extension sets a project up as the Gradle plugin does. */
class LifecycleParticipantTest {
    private static MavenProject project(String generation) {
        Model model = new Model();
        model.setGroupId("acme");
        model.setArtifactId("orders");
        model.setVersion("1.0.0");
        model.setBuild(new Build());
        Plugin toolgate = new Plugin();
        toolgate.setGroupId("io.ololabs.toolgate");
        toolgate.setArtifactId("toolgate-maven-plugin");
        toolgate.setVersion("2.0.0");
        if (generation != null) {
            Xpp3Dom configuration = new Xpp3Dom("configuration");
            Xpp3Dom g = new Xpp3Dom("descriptorGeneration");
            g.setValue(generation);
            configuration.addChild(g);
            toolgate.setConfiguration(configuration);
        }
        model.getBuild().addPlugin(toolgate);
        // As model building leaves it: the default lifecycle's compiler plugin, its configuration copied into executions.
        Plugin compiler = new Plugin();
        compiler.setGroupId("org.apache.maven.plugins");
        compiler.setArtifactId("maven-compiler-plugin");
        org.apache.maven.model.PluginExecution compile = new org.apache.maven.model.PluginExecution();
        compile.setId("default-compile");
        compile.setConfiguration(new Xpp3Dom("configuration"));
        compiler.addExecution(compile);
        model.getBuild().addPlugin(compiler);
        return new MavenProject(model);
    }

    @Test
    void theSdkTheProcessorAndTheGenerationAreAdded() {
        MavenProject project = project("1");
        ToolGateLifecycleParticipant.configure(project);
        ToolGateLifecycleParticipant.configure(project);
        assertEquals(List.of("toolgate-sdk-api:2.0.0:compile", "toolgate-sdk-runtime-mcp:2.0.0:runtime", "toolgate-sdk-testing:2.0.0:test"),
                project.getDependencies().stream().map(d -> d.getArtifactId() + ":" + d.getVersion() + ":" + d.getScope()).collect(Collectors.toList()));
        Xpp3Dom compiler = (Xpp3Dom) ToolGateLifecycleParticipant.plugin(project, ToolGateLifecycleParticipant.COMPILER).getConfiguration();
        Xpp3Dom[] paths = compiler.getChild("annotationProcessorPaths").getChildren();
        assertEquals(1, paths.length, "added once");
        assertEquals("toolgate-sdk-processor", paths[0].getChild("artifactId").getValue());
        assertEquals("2.0.0", paths[0].getChild("version").getValue());
        Xpp3Dom[] args = compiler.getChild("compilerArgs").getChildren();
        assertEquals(1, args.length);
        assertEquals("-Atoolgate.descriptorGeneration=1", args[0].getValue());
        Xpp3Dom execution = (Xpp3Dom) ToolGateLifecycleParticipant.plugin(project, ToolGateLifecycleParticipant.COMPILER)
                .getExecutionsAsMap().get("default-compile").getConfiguration();
        assertEquals("-Atoolgate.descriptorGeneration=1", execution.getChild("compilerArgs").getChildren()[0].getValue(), "executions too");
        assertEquals(1, execution.getChild("annotationProcessorPaths").getChildren().length);
    }

    @Test
    void withoutAGenerationTheProcessorReportsItMissing() {
        MavenProject project = project(null);
        ToolGateLifecycleParticipant.configure(project);
        Xpp3Dom compiler = (Xpp3Dom) ToolGateLifecycleParticipant.plugin(project, ToolGateLifecycleParticipant.COMPILER).getConfiguration();
        assertNull(compiler.getChild("compilerArgs"));
    }

    @Test
    void projectsWithoutThePluginAreLeftAlone() {
        Model model = new Model();
        model.setBuild(new Build());
        MavenProject project = new MavenProject(model);
        ToolGateLifecycleParticipant.configure(project);
        assertEquals(List.of(), project.getDependencies());
    }
}
