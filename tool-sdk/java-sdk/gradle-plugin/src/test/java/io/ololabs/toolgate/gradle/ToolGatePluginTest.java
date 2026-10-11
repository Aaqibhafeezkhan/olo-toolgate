// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

/** Conformance: dev-server/build-plugins for Gradle. */
class ToolGatePluginTest {
    private static Project project() {
        Project project = ProjectBuilder.builder().withName("orders").build();
        project.getPluginManager().apply(ToolGatePlugin.ID);
        return project;
    }

    @Test
    void devPackageAndCheckTasksExist() {
        Project project = project();
        JavaExec dev = (JavaExec) project.getTasks().getByName("toolgateDev");
        assertEquals(ToolGatePlugin.RUNTIME_MAIN, dev.getMainClass().get());
        assertEquals("development", dev.getEnvironment().get("TOOLGATE_MODE"));
        JavaExec check = (JavaExec) project.getTasks().getByName("toolgateCheck");
        assertEquals(ToolGatePlugin.BUILD_MAIN, check.getMainClass().get());
        assertEquals("check", check.getArgs().get(0));
        JavaExec pack = (JavaExec) project.getTasks().getByName("toolgatePackage");
        assertEquals(ToolGatePlugin.BUILD_MAIN, pack.getMainClass().get());
        Task lifecycle = project.getTasks().getByName("check");
        assertTrue(lifecycle.getTaskDependencies().getDependencies(lifecycle).contains(check), "check runs toolgateCheck");
        assertTrue(pack.getTaskDependencies().getDependencies(pack).contains(project.getTasks().getByName("jar")));
    }

    @Test
    void descriptorGenerationIsPassedOnlyWhenSet() {
        Project project = project();
        JavaCompile compile = (JavaCompile) project.getTasks().getByName("compileJava");
        assertEquals(List.of(), arguments(compile), "required with no default: the processor reports it missing");
        project.getExtensions().getByType(ToolGateExtension.class).getDescriptorGeneration().set(1);
        assertEquals(List.of("-Atoolgate.descriptorGeneration=1"), arguments(compile));
    }

    @Test
    void theSdkLibrariesAreAddedAtTheSdkVersion() {
        Project project = project();
        project.getExtensions().getByType(ToolGateExtension.class).getSdkVersion().set("1.2.3");
        assertEquals(List.of("io.ololabs.toolgate:toolgate-sdk-api:1.2.3"), dependencies(project, "implementation"));
        assertEquals(List.of("io.ololabs.toolgate:toolgate-sdk-processor:1.2.3"), dependencies(project, "annotationProcessor"));
        assertEquals(List.of("io.ololabs.toolgate:toolgate-sdk-runtime-mcp:1.2.3"), dependencies(project, "runtimeOnly"));
        assertEquals(List.of("io.ololabs.toolgate:toolgate-sdk-testing:1.2.3"), dependencies(project, "testImplementation"));
        assertEquals(List.of("io.ololabs.toolgate:toolgate-sdk-build:1.2.3"), dependencies(project, "toolgateBuild"));
        assertFalse(ToolGatePlugin.ownVersion().contains("$"), "the plugin's version is filled in at build time");
    }

    private static List<String> arguments(JavaCompile compile) {
        return compile.getOptions().getCompilerArgumentProviders().stream()
                .flatMap(p -> StreamSupport.stream(p.asArguments().spliterator(), false)).collect(Collectors.toList());
    }

    private static List<String> dependencies(Project project, String configuration) {
        return project.getConfigurations().getByName(configuration).getDependencies().stream()
                .map(d -> d.getGroup() + ":" + d.getName() + ":" + d.getVersion()).collect(Collectors.toList());
    }
}
