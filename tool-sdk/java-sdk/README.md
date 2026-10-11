<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Tool SDK for Java

## Purpose

Java 17+ implementation of the [authoring contract](../spec/authoring/README.md), exactly as [authoring/java.md](../spec/authoring/java.md) names it. This is milestone M0 part 2: the authoring API and the annotation processor that turns it into semantic descriptors. The runtime (standalone and governed), the Gradle and Maven plugins, typed accessors and the route convenience analyser arrive in later parts.

## Modules

| Module | Artifact | Contents |
|---|---|---|
| [`api/`](api/) | `toolgate-sdk-api` | `io.ololabs.toolgate.sdk.annotations` (the declarations), `.context` (`ToolContext` and the route client interfaces), `.errors` (the [error hierarchy](../spec/authoring/errors.md)) |
| [`processor/`](processor/) | `toolgate-sdk-processor` | `DescriptorProcessor`, a javac annotation processor |

Both compile with `--release 17` and have no runtime dependencies.

## Using the processor

```kotlin
dependencies {
    implementation("io.ololabs.toolgate:toolgate-sdk-api:<version>")
    annotationProcessor("io.ololabs.toolgate:toolgate-sdk-processor:<version>")
}
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Atoolgate.descriptorGeneration=1")
}
```

The generation setting is required and has no default (descriptor spec §1, case `descriptors/generation-required`); the Gradle and Maven plugins will set it from `descriptorGeneration`.

For each tool the processor writes `META-INF/toolgate/tools/<tool local name>.json`: the generation-1 semantic descriptor as its canonical bytes (NFC, then RFC 8785), so `sha256` of the file is the `toolDescriptorDigest`. It also writes `META-INF/toolgate/package.json` with the package id, name and publisher and, per tool, the digest, the handler class and method, and the parameter order the runtime binds arguments in.

## What the processor checks

A violation is a compile error at the declaration:

- identifier grammars, the 128-character `toolId`, and the reserved `toolgate.`, `hotfolder.` and `builtin.` prefixes;
- exactly one `@ToolPackage`, unique tool names, and route, destination, auth and TCP ids unique per package;
- `title` and `text` exclusive, required descriptions, length limits, and `maxDurationMs` within the platform ceiling (60 s sync, 15 min async);
- variable names unique per tool across inputs, globals, secrets and claims, ignoring case, and no tool-level declaration with the name of a package- or class-level one;
- client tools without routes, TCP, secrets or `@HttpAuth`;
- routes: declared destination and auth, no `Authorization`, `Proxy-Authorization`, `Cookie`, `Host`, `X-Forwarded-*` or auth header in the allowlist, path variables that are variable names with patterns other than `.*` and `.+`;
- a reachable auth's secret declared by the tool, `businessKeyArgument` naming an input, at most 8 examples;
- constraint keywords that fit the value type, defaults that parse as the type, and patterns that compile.

## Declaration scope

Globals, secrets and claims on the `@ToolPackage` class apply to every tool; on a handler class, to the tools in that class; on a handler (or in `@ToolVariables`), to that tool. Destinations, auth, routes and TCP destinations may be declared on any class and are resolved per package. A tool's descriptor carries only the routes it lists and the destinations and auth they reach.

## Tests

`./gradlew :sdk-java-processor:test` compiles fixture sources with the processor. `DescriptorVectorTest` compares the output with the [descriptor vectors](../spec/descriptor/vectors/) byte for byte; `DeclarationTest` covers the declaration rules. `ConformanceTest` lists which test runs which [conformance case](../conformance/) for the `java` target. Those cases also target the other SDKs, so they stay `authored` until every SDK passes them.

## Known gaps

- Descriptor vector tool-03 declares the route path `/orders/{orderId}/refunds`, a variable without a pattern, which [http.md](../spec/authoring/http.md) §1 and case `route-analysis/path-variable-regex` refuse. The vectors are normative, so the processor accepts a variable without a pattern for now and refuses only `.*` and `.+`. The vector or the rule needs an amendment.
- Typed accessors (`OrderToolsVars.global(context)`), compile errors for undeclared names in `get*Value` calls, undeclared literal error codes, and the convenience-route analyser need the javac Tree API and arrive with the runtime.
