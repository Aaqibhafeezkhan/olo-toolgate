// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.bundling.Jar;

/**
 * Plugin {@code io.ololabs.toolgate.sdk} (java.md): adds the SDK, the processor, the standalone runtime and the
 * testing library to a Java project, passes {@code descriptorGeneration} to the processor, and registers
 * <ul>
 *   <li>{@code toolgateDev}: serves the tools over stdio in development mode, with {@code toolgate.dev.json};</li>
 *   <li>{@code toolgateCheck}: runs every {@code @Example} in test mode and checks the dev file is untracked; part of {@code check};</li>
 *   <li>{@code toolgatePackage}: stages the package and packs it with the {@code toolgate} CLI into {@code build/toolgate}.</li>
 * </ul>
 * Tool code always runs in a forked JVM, never in the Gradle daemon.
 */
public final class ToolGatePlugin implements Plugin<Project> {
    public static final String ID = "io.ololabs.toolgate.sdk";
    static final String GROUP = "io.ololabs.toolgate";
    static final String RUNTIME_MAIN = "io.ololabs.toolgate.runtime.Main";
    static final String BUILD_MAIN = "io.ololabs.toolgate.sdkbuild.Main";
    static final String TASK_GROUP = "toolgate";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        ToolGateExtension toolgate = project.getExtensions().create("toolgate", ToolGateExtension.class);
        toolgate.getSdkVersion().convention(ownVersion());

        DependencyHandler dependencies = project.getDependencies();
        add(project, dependencies, toolgate, JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, "toolgate-sdk-api");
        add(project, dependencies, toolgate, JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME, "toolgate-sdk-processor");
        add(project, dependencies, toolgate, JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME, "toolgate-sdk-runtime-mcp");
        add(project, dependencies, toolgate, JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME, "toolgate-sdk-testing");
        Configuration build = project.getConfigurations().create("toolgateBuild", c -> {
            c.setDescription("toolgate-sdk-build, which toolgateCheck and toolgatePackage run");
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
        });
        add(project, dependencies, toolgate, build.getName(), "toolgate-sdk-build");

        project.getTasks().withType(JavaCompile.class).configureEach(
                t -> t.getOptions().getCompilerArgumentProviders().add(new GenerationArgument(toolgate.getDescriptorGeneration())));

        SourceSet main = project.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        project.getTasks().register("toolgateDev", JavaExec.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Serves the tools over stdio in development mode, with toolgate.dev.json fixtures");
            t.classpath(main.getRuntimeClasspath());
            t.getMainClass().set(RUNTIME_MAIN);
            t.environment("TOOLGATE_MODE", "development");
            t.setWorkingDir(project.getProjectDir());
            t.setStandardInput(System.in);
        });
        var check = project.getTasks().register("toolgateCheck", JavaExec.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Runs every @Example in test mode and checks that toolgate.dev.json is not tracked");
            t.classpath(main.getRuntimeClasspath(), build);
            t.getMainClass().set(BUILD_MAIN);
            t.args("check", project.getProjectDir().getAbsolutePath());
        });
        project.getTasks().named("check", t -> t.dependsOn(check));
        project.getTasks().register("toolgatePackage", JavaExec.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Stages the package and packs it with the toolgate CLI into build/toolgate");
            var jar = project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class);
            t.dependsOn(jar, check);
            t.classpath(build);
            t.getMainClass().set(BUILD_MAIN);
            var objects = project.getObjects();
            var layout = project.getLayout();
            var jarFile = objects.fileProperty().value(jar.flatMap(Jar::getArchiveFile));
            var version = objects.property(String.class).value(project.provider(() -> String.valueOf(project.getVersion())));
            var staging = objects.directoryProperty().value(layout.getBuildDirectory().dir("toolgate/staging"));
            String name = project.getName();
            var output = objects.fileProperty().value(layout.getBuildDirectory().file(version.map(v -> "toolgate/" + name + "-" + v + ".tgpkg")));
            t.getArgumentProviders().add(new PackageArguments(jarFile,
                    project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME), version, staging, output));
        });
    }

    private static void add(Project project, DependencyHandler dependencies, ToolGateExtension toolgate, String configuration, String artifact) {
        project.getConfigurations().named(configuration, c -> c.getDependencies().addLater(
                toolgate.getSdkVersion().map(v -> dependencies.create(GROUP + ":" + artifact + ":" + v))));
    }

    /** The version this plugin was built with. */
    static String ownVersion() {
        Properties p = new Properties();
        try (InputStream in = ToolGatePlugin.class.getResourceAsStream("plugin.properties")) {
            if (in == null) throw new IllegalStateException("plugin.properties is missing from the ToolGate Gradle plugin");
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p.getProperty("sdkVersion");
    }
}
