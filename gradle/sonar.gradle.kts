// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
// Applied only for the explicit sonar task; analysis failure remains a failure.
buildscript {
    repositories { gradlePluginPortal() }
    dependencies {
        classpath("org.sonarsource.scanner.gradle:sonarqube-gradle-plugin:7.5.0.8588")
    }
}

apply<org.sonarqube.gradle.SonarQubePlugin>()

extensions.configure<org.sonarqube.gradle.SonarExtension> {
    properties {
        property("sonar.projectKey", providers.environmentVariable("SONAR_PROJECT_KEY").getOrElse("olo-labs_olo-toolgate"))
        providers.environmentVariable("SONAR_ORGANIZATION").orNull?.takeIf { it.isNotBlank() }?.let {
            property("sonar.organization", it)
        }
        property("sonar.qualitygate.wait", "false")
        // Our .sql files target PostgreSQL/SQLite, not Oracle (where '' is NULL).
        // Keep Oracle analysis on Oracle suffixes; both actual SQL engines have executable gates.
        property("sonar.plsql.file.suffixes", "pks,pkb,pls,plb")
        property("sonar.sources", "tools,apps/admin-ui/src,apps/browser-extension,apps/quickstart,apps/gateway/src,apps/endpoint-client/src,apps/tool-host/src,tool-sdk/cli/src,tool-sdk/python-sdk/src,packages/contracts/typescript/src,packages/contracts/rust/src,packages/tenant-identity")
        property("sonar.exclusions", "**/tests/**,**/test/**,**/*.test.*,**/*.d.ts,**/build/**,**/dist/**,**/node_modules/**")
        property("sonar.javascript.lcov.reportPaths", "build/coverage/typescript/lcov.info,build/coverage/javascript/lcov.info")
        property("sonar.python.coverage.reportPaths", "build/coverage/python.xml")
        property("sonar.rust.lcov.reportPaths", "build/coverage/rust.lcov")
    }
}

subprojects {
    plugins.withType<org.gradle.api.plugins.JavaPlugin> {
        extensions.configure<org.sonarqube.gradle.SonarExtension> {
            properties {
                property("sonar.coverage.jacoco.xmlReportPaths", rootProject.layout.buildDirectory.file("reports/jacoco/jacocoRootReport/jacocoRootReport.xml").get().asFile.absolutePath)
            }
        }
    }
}
