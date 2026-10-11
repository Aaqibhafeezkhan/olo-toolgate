// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

/** The arguments of {@code stage} in toolgate-sdk-build: the tool jar, its runtime libraries, the version and where to write. */
final class PackageArguments implements CommandLineArgumentProvider {
    private final RegularFileProperty jar;
    private final FileCollection libraries;
    private final Property<String> version;
    private final DirectoryProperty staging;
    private final RegularFileProperty output;

    PackageArguments(RegularFileProperty jar, FileCollection libraries, Property<String> version, DirectoryProperty staging, RegularFileProperty output) {
        this.jar = jar;
        this.libraries = libraries;
        this.version = version;
        this.staging = staging;
        this.output = output;
    }

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public RegularFileProperty getJar() {
        return jar;
    }

    @Classpath
    public FileCollection getLibraries() {
        return libraries;
    }

    @Input
    public Property<String> getVersion() {
        return version;
    }

    @OutputDirectory
    public DirectoryProperty getStaging() {
        return staging;
    }

    @OutputFile
    public RegularFileProperty getOutput() {
        return output;
    }

    @Override
    public Iterable<String> asArguments() {
        List<String> args = new ArrayList<>(List.of("stage", "--jar", jar.get().getAsFile().getPath(), "--version", version.get(),
                "--out", staging.get().getAsFile().getPath()));
        for (File lib : libraries) {
            args.add("--lib");
            args.add(lib.getPath());
        }
        args.add("--pack");
        args.add(output.get().getAsFile().getPath());
        return args;
    }
}
