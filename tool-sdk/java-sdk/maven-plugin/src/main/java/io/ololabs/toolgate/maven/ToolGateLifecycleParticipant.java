// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.maven;

import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * With {@code <extensions>true</extensions>}, sets up a project before Maven plans its build, as the Gradle plugin
 * does: adds the SDK ({@code compile}), the standalone runtime ({@code runtime}) and the testing library
 * ({@code test}) at the plugin's version, puts the processor on the compiler plugin's {@code annotationProcessorPaths}
 * (so it runs on every JDK; other processors must then be listed there too), and passes the configured
 * {@code descriptorGeneration} to it.
 */
@Named("toolgate")
@Singleton
public class ToolGateLifecycleParticipant extends AbstractMavenLifecycleParticipant {
    static final String GROUP = "io.ololabs.toolgate";
    static final String PLUGIN = GROUP + ":toolgate-maven-plugin";
    static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";

    @Override
    public void afterProjectsRead(MavenSession session) {
        for (MavenProject project : session.getProjects()) configure(project);
    }

    static void configure(MavenProject project) {
        Plugin toolgate = plugin(project, PLUGIN);
        if (toolgate == null) return;
        String version = toolgate.getVersion();
        add(project, "toolgate-sdk-api", version, "compile");
        add(project, "toolgate-sdk-runtime-mcp", version, "runtime");
        add(project, "toolgate-sdk-testing", version, "test");
        // Model building already injected the compiler plugin of the default lifecycle; a bare model may lack it.
        Plugin compiler = plugin(project, COMPILER);
        if (compiler == null) {
            compiler = new Plugin();
            compiler.setGroupId("org.apache.maven.plugins");
            compiler.setArtifactId("maven-compiler-plugin");
            project.getBuild().addPlugin(compiler);
        }
        // Model building already copied the plugin configuration into each execution, so both get the change.
        String generation = generation(toolgate);
        compiler.setConfiguration(compilerConfiguration(compiler.getConfiguration(), version, generation));
        for (PluginExecution execution : compiler.getExecutions()) {
            execution.setConfiguration(compilerConfiguration(execution.getConfiguration(), version, generation));
        }
    }

    /** {@code configuration} with the processor on its processor path and, when set, the generation option. */
    private static Xpp3Dom compilerConfiguration(Object existing, String version, String generation) {
        Xpp3Dom configuration = existing instanceof Xpp3Dom c ? c : new Xpp3Dom("configuration");
        Xpp3Dom paths = child(configuration, "annotationProcessorPaths");
        boolean listed = false;
        for (Xpp3Dom path : paths.getChildren()) {
            listed |= path.getChild("artifactId") != null && "toolgate-sdk-processor".equals(path.getChild("artifactId").getValue());
        }
        if (!listed) {
            Xpp3Dom path = new Xpp3Dom("path");
            value(path, "groupId", GROUP);
            value(path, "artifactId", "toolgate-sdk-processor");
            value(path, "version", version);
            paths.addChild(path);
        }
        // Required with no default: without it the processor fails the compilation and says so.
        if (generation != null) {
            Xpp3Dom args = child(configuration, "compilerArgs");
            String option = "-Atoolgate.descriptorGeneration=";
            boolean set = false;
            for (Xpp3Dom arg : args.getChildren()) set |= arg.getValue() != null && arg.getValue().startsWith(option);
            if (!set) value(args, "arg", option + generation);
        }
        return configuration;
    }

    /** The plugin with this key; getPluginsAsMap() is a snapshot that misses plugins added later. */
    static Plugin plugin(MavenProject project, String key) {
        for (Plugin p : project.getBuild().getPlugins()) if (key.equals(p.getKey())) return p;
        return null;
    }

    private static Xpp3Dom child(Xpp3Dom parent, String name) {
        Xpp3Dom child = parent.getChild(name);
        if (child == null) {
            child = new Xpp3Dom(name);
            parent.addChild(child);
        }
        return child;
    }

    private static void value(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
    }

    private static String generation(Plugin toolgate) {
        if (toolgate.getConfiguration() instanceof Xpp3Dom c && c.getChild("descriptorGeneration") != null) {
            return c.getChild("descriptorGeneration").getValue().trim();
        }
        return null;
    }

    private static void add(MavenProject project, String artifact, String version, String scope) {
        for (Dependency d : project.getDependencies()) {
            if (GROUP.equals(d.getGroupId()) && artifact.equals(d.getArtifactId())) return;
        }
        Dependency d = new Dependency();
        d.setGroupId(GROUP);
        d.setArtifactId(artifact);
        d.setVersion(version);
        d.setScope(scope);
        project.getModel().addDependency(d);
    }
}
