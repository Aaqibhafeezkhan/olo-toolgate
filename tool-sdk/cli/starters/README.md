<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Starters

`toolgate init` writes a new tool project from the files here. `build.rs` embeds them in the binary, so `init` works offline.

## Format

- A starter is a stack of **layers**: `<language>/common`, then `<language>/<build tool>`. `src/init.rs` lists the starters (`STARTERS`); the first one for a language is its default build tool.
- A file ending in `.tmpl` is a template. `init` drops the suffix and fills each `{{variable}}`. An unknown variable fails the starter's unit test, so a typo never reaches a project. Every starter gets `name`, `title`, `packageId`, `publisher` and `sdkVersion`; a language adds its own in `variables()` (Java: `javaPackage`, `packagePath`, `className`, `group`, and the repository snippets).
- In paths, `__PACKAGE__` becomes `packagePath` and `__CLASS__` becomes `className`.
- Other files are copied as they are and keep their executable bit. The Java Gradle layer carries this repository's Gradle bootstrap scripts (`gradlew`, `gradlew.bat`, `gradle/`); a unit test keeps them identical to the ones at the repository root.
- Templates carry no license header: the project belongs to whoever runs `init`. The `.tmpl` suffix also keeps them out of this repository's own build, header check and `.gitignore`.

## Adding a language

1. Add `starters/<language>/common` and one layer per build tool. The starter should hold one tool without network access, one that calls a declared route with a recorded exchange, a `@Example` on each, and a unit test with the language's harness.
2. Add its `Starter` entries and variables in `src/init.rs`.
3. Add an `Adapter` in `src/project.rs`: the marker file, and the commands that run development mode, check and package. The CLI never compiles code itself; it drives the language's own build, which already implements test mode ([testing.md](../../spec/authoring/testing.md)) and stages the package.
4. Add an end-to-end run like [java-sdk/e2e/run.sh](../../java-sdk/e2e/run.sh): `init`, `check`, `pack`, `dev` and `dev --digests`, with every build giving the same `descriptorSetDigest`.
