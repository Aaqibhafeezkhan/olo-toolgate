// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
plugins {
    java
    alias(libs.plugins.quarkus)
}

val usePublishedContracts = providers.gradleProperty("usePublishedContracts").map(String::toBoolean).orElse(false)
// Pin initial configuration to the built release; upgrades never reseed stored tenant state.
val releaseConfigurations = groovy.json.JsonSlurper().parse(rootProject.file("config/initial/releases.json")) as Map<*, *>
val releaseConfiguration = (releaseConfigurations["releases"] as Map<*, *>)[rootProject.file("VERSION").readText().trim()] as String
val initialConfiguration = releaseConfiguration
check(initialConfiguration.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid initial configuration bundle" }
val initialConfigurationDirectory = rootProject.file("config/initial/$initialConfiguration")
val contractsVersion = providers.gradleProperty("contractsVersion").getOrElse(rootProject.file("packages/contracts/VERSION").readText().trim())
dependencies {
    testImplementation(project(":service-authorization"))
    implementation(enforcedPlatform(libs.quarkus.bom))
    listOf("rest-jackson", "jdbc-postgresql", "flyway", "smallrye-jwt", "smallrye-health",
        "micrometer-registry-prometheus", "opentelemetry", "logging-json", "smallrye-openapi")
        .forEach { implementation("io.quarkus:quarkus-$it") }
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.sqlite.jdbc)
    implementation(libs.jackson.yaml)
    implementation(libs.json.schema)
    implementation(libs.bouncycastle.pkix)
    implementation(project(":package-archive"))
    implementation(project(":sdk-java-api"))
    implementation(project(":sdk-json"))
    if (usePublishedContracts.get()) {
        implementation("io.github.olo-labs:toolgate-contracts:$contractsVersion")
    } else {
        implementation(project(":contracts-java"))
    }
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    systemProperty("toolgate.published", usePublishedContracts.get())
    systemProperty("toolgate.fixtures", rootProject.file("tests/fixtures/contracts/v1/valid.json").absolutePath)
    systemProperty("toolgate.productVersion", rootProject.file("VERSION").readText().trim())
    // A fresh PostgreSQL boundary is supplied for each verification invocation.
    outputs.upToDateWhen { false }
}

// Worker proof has separate reports so the full regression evidence remains intact.
tasks.register<Test>("sdkWorkerTest") {
    description = "Builds real JSON SDK packages through the isolated worker and checks byte equality and cleanup"
    dependsOn("testClasses", ":sdk-json:installDist")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("io.ololabs.toolgate.control.JsonSdkWorkerTest") }
    val cli = providers.environmentVariable("TOOLGATE_CLI").getOrElse("")
    doFirst { require(cli.isNotBlank()) { "sdkWorkerTest requires TOOLGATE_CLI pointing to the verified native package CLI" } }
    systemProperty("toolgate.cli", cli)
    systemProperty("toolgate.productVersion", rootProject.file("VERSION").readText().trim())
    systemProperty("toolgate.fixtures", rootProject.file("tests/fixtures/contracts/v1/valid.json").absolutePath)
    systemProperty("toolgate.sdkLibraries", rootProject.file("tool-sdk/json-sdk/build/install/toolgate-json-sdk/lib").absolutePath)
    systemProperty("toolgate.workerWorkRoot", rootProject.file("build/sdk-worker-e2e").absolutePath)
    outputs.upToDateWhen { false }
}

// Security conformance has an independent source set so it can run during migration
// work without weakening the ordinary regression suite or its compile boundaries.
val enterpriseTests = sourceSets.create("enterpriseTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
sourceSets.test { java.srcDir("src/sharedTest/java") }
sourceSets.test { resources.srcDir("src/enterpriseTest/resources") }
enterpriseTests.java.srcDir("src/sharedTest/java")
// The enforced Quarkus 3.40 BOM pins Guava 33.6.0; 33.7.2 fixes CVE-2026-102554.
val guava = libs.guava.get()
// It also pins OpenTelemetry 1.62.0; 1.67.0 fixes CVE-2026-118259. The core artifacts
// share one release train, so align them all (alpha modules keep their -alpha suffix).
val openTelemetry = libs.opentelemetry.api.get()
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == guava.group && requested.name == guava.name) {
            useVersion(guava.version!!)
            because("CVE-2026-102554")
        }
        if (requested.group == openTelemetry.group && requested.version?.startsWith("1.62.0") == true) {
            useVersion(requested.version!!.replace("1.62.0", openTelemetry.version!!))
            because("CVE-2026-118259")
        }
    }
}
configurations[enterpriseTests.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[enterpriseTests.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
tasks.register<Test>("enterpriseTest") {
    description = "Runs enterprise group graph and authorization conformance tests"
    testClassesDirs = enterpriseTests.output.classesDirs
    classpath = enterpriseTests.runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
}
tasks.check { dependsOn("enterpriseTest") }

val consoleDirectory = rootProject.file("apps/admin-ui/dist")
val prebuiltConsole = providers.gradleProperty("prebuiltAdminUi").map(String::toBoolean).getOrElse(false)
val buildAdminUi = tasks.register<Exec>("buildAdminUi") {
    workingDir(rootProject.projectDir)
    commandLine("node", "tools/ui/build.mjs")
    inputs.files(rootProject.fileTree("apps/admin-ui") { exclude("dist/**", "node_modules/**", "coverage/**", "test-results/**") })
    inputs.files(rootProject.fileTree("packages/contracts/typescript/src"), rootProject.file("package-lock.json"), rootProject.file("VERSION"), rootProject.file("LICENSE"), rootProject.file("tools/ui/build.mjs"))
    inputs.files(rootProject.fileTree("config/initial"))
    outputs.dir(consoleDirectory)
    enabled = !prebuiltConsole
}

abstract class VerifyConsoleArtifact : DefaultTask() {
    @get:InputFile abstract val metadata: RegularFileProperty
    @get:Input abstract val releaseVersion: Property<String>
    @get:Input abstract val contractVersion: Property<String>
    @TaskAction fun verify() {
        val content = metadata.get().asFile.readText()
        check(content.contains("\"version\": \"${releaseVersion.get()}\"") && content.contains("\"contracts\": \"${contractVersion.get()}\"")) {
            "Admin UI artifact version mismatch; run node tools/ui/build.mjs"
        }
    }
}
val verifyAdminUi = tasks.register<VerifyConsoleArtifact>("verifyAdminUi") {
    dependsOn(buildAdminUi)
    metadata.set(consoleDirectory.resolve("release.json"))
    releaseVersion.set(rootProject.file("VERSION").readText().trim())
    contractVersion.set(contractsVersion)
}

tasks.processResources {
    dependsOn(verifyAdminUi)
    from(rootProject.file("VERSION")) {
        into("META-INF")
        rename { "toolgate-product.version" }
    }
    from(consoleDirectory) { into("META-INF/resources/console"); exclude(".vite/**") }
    from(initialConfigurationDirectory) { into("initial-configuration"); include("*.json") }
    from(rootProject.file("packages/contracts/openapi/control-v1.yaml")) {
        into("META-INF")
        rename { "openapi.yaml" }
    }
}
