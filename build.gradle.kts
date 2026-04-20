import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    `java-library`
    jacoco
}

allprojects {
    group = "dev.daisybase"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

val requiredDocumentationFiles = listOf(
    "README.md",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "SUPPORT.md",
    "CODE_OF_CONDUCT.md",
    ".github/ISSUE_TEMPLATE/config.yml",
    ".github/ISSUE_TEMPLATE/bug-report.yml",
    ".github/ISSUE_TEMPLATE/feature-request.yml",
    ".github/ISSUE_TEMPLATE/documentation.yml",
    ".github/PULL_REQUEST_TEMPLATE.md",
    "docs/README.md",
    "docs/50-point-documentation-plan.md",
    "docs/guides/read-me-first.md",
    "docs/guides/choose-your-runtime.md",
    "docs/guides/how-daisybase-thinks.md",
    "docs/guides/building-an-app-with-jdbc-and-orm.md",
    "docs/getting-started/quickstart.md",
    "docs/getting-started/installers.md",
    "docs/architecture/system-overview.md",
    "docs/architecture/module-map.md",
    "docs/architecture/storage-recovery.md",
    "docs/architecture/query-lifecycle.md",
    "docs/reference/runtime-surfaces.md",
    "docs/reference/sql-surface.md",
    "docs/reference/jdbc-surface.md",
    "docs/reference/security-and-distributed-xa.md",
    "docs/reference/operations-runbook.md",
    "docs/reference/testing-and-quality.md",
    "docs/reference/demo-business-app.md",
    "docs/reference/orm-tooling.md",
    "docs/reference/known-limits-and-roadmap.md",
    "docs/site/index.html",
    "docs/site/styles.css",
    "docs/system/daisybase-system-catalog.json",
    "docs/mcp-description-system.md",
    "tools/daisybase-system-mcp/README.md",
    "tools/daisybase-system-mcp/server.py",
    "tools/daisybase-system-mcp/test_server.py",
    "tools/daisybase-orm-mcp/README.md",
    "tools/daisybase-orm-mcp/server.py",
    "tools/daisybase-orm-mcp/test_server.py",
    "scripts/validate-docs.ps1",
    "scripts/validate-docs.sh"
)

tasks.register("validateDocs") {
    description = "Validates the documentation system layout and accessibility anchors."
    group = "verification"

    doLast {
        requiredDocumentationFiles.forEach { relativePath ->
            val artifact = rootProject.file(relativePath)
            check(artifact.exists()) { "Missing documentation artifact: $relativePath" }
        }

        val portal = rootProject.file("docs/site/index.html").readText()
        check("Skip to main content" in portal) { "Docs portal is missing the skip link." }
        check("id=\"main-content\"" in portal) { "Docs portal is missing the main content landmark." }

        val catalog = rootProject.file("docs/system/daisybase-system-catalog.json").readText()
        check("\"implementedPoints\": 50" in catalog) { "Documentation coverage plan count is not 50." }
        check("\"modules\"" in catalog) { "System catalog is missing module data." }
    }
}

tasks.named("check") {
    dependsOn("validateDocs")
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            showExceptions = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    dependencies {
        testImplementation(platform("org.junit:junit-bom:5.12.2"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    jacoco {
        toolVersion = "0.8.13"
    }
}

fun Project.enableJacocoXmlAndHtmlReports() {
    tasks.named<JacocoReport>("jacocoTestReport") {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}

fun Project.requireBundleCoverage(minimumLine: String, minimumBranch: String) {
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn("test")
        violationRules {
            rule {
                element = "BUNDLE"
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = minimumLine.toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    value = "COVEREDRATIO"
                    minimum = minimumBranch.toBigDecimal()
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn("jacocoTestCoverageVerification")
    }
}

project(":common")

project(":installer") {
    apply(plugin = "application")
    the<JavaApplication>().mainClass.set("dev.daisybase.installer.InstallerMain")
}

project(":jdbc") {
    dependencies {
        api(project(":common"))
        api(project(":engine-api"))
        implementation(project(":catalog"))
        implementation(project(":server"))
    }
}

project(":orm") {
    apply(plugin = "application")
    the<JavaApplication>().mainClass.set("dev.daisybase.orm.DaisyBaseOrmCli")

    dependencies {
        api(project(":jdbc"))
        implementation(project(":common"))
        implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
        testImplementation(project(":engine-api"))
    }
}

project(":demo-business-app") {
    apply(plugin = "war")

    dependencies {
        implementation(project(":jdbc"))
        implementation(project(":engine-api"))
        implementation(project(":common"))
        compileOnly("jakarta.platform:jakarta.jakartaee-web-api:10.0.0")
        testImplementation("jakarta.platform:jakarta.jakartaee-web-api:10.0.0")
        testImplementation(project(":jdbc"))
        testImplementation(project(":engine-api"))
    }

    tasks.named<org.gradle.api.tasks.bundling.War>("war") {
        archiveBaseName.set("daisybase-demo-business")
    }
}

project(":sql-frontend") {
    dependencies {
        api(project(":common"))
        implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    }

    enableJacocoXmlAndHtmlReports()

    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn("test")
        violationRules {
            rule {
                element = "BUNDLE"
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.85".toBigDecimal()
                }
            }
            rule {
                element = "CLASS"
                includes = listOf("dev.daisybase.sql.SqlFrontend\$Parser")
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.85".toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    value = "COVEREDRATIO"
                    minimum = "0.75".toBigDecimal()
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn("jacocoTestCoverageVerification")
    }
}

project(":storage") {
    enableJacocoXmlAndHtmlReports()
    requireBundleCoverage("0.84", "0.68")

    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        violationRules {
            rule {
                element = "CLASS"
                includes = listOf("dev.daisybase.storage.HeapStorageManager")
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.82".toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    value = "COVEREDRATIO"
                    minimum = "0.67".toBigDecimal()
                }
            }
        }
    }
}

project(":wal") {
    enableJacocoXmlAndHtmlReports()
    requireBundleCoverage("0.87", "0.84")

    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        violationRules {
            rule {
                element = "CLASS"
                includes = listOf("dev.daisybase.wal.Wal\$WalManager")
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.86".toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    value = "COVEREDRATIO"
                    minimum = "0.85".toBigDecimal()
                }
            }
        }
    }
}

project(":engine-api") {
    enableJacocoXmlAndHtmlReports()
    requireBundleCoverage("0.88", "0.72")

    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        violationRules {
            rule {
                element = "CLASS"
                includes = listOf("dev.daisybase.engine.RemoteProtocol")
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.95".toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    value = "COVEREDRATIO"
                    minimum = "0.90".toBigDecimal()
                }
            }
        }
    }
}

project(":catalog") {
    dependencies {
        api(project(":common"))
        api(project(":sql-frontend"))
    }
}

project(":txn") {
    dependencies {
        api(project(":common"))
        api(project(":catalog"))
    }
}

project(":storage") {
    dependencies {
        api(project(":common"))
        api(project(":catalog"))
        api(project(":txn"))
    }
}

project(":index") {
    dependencies {
        api(project(":common"))
        api(project(":catalog"))
        api(project(":storage"))
    }
}

project(":planner") {
    dependencies {
        api(project(":common"))
        api(project(":sql-frontend"))
        api(project(":catalog"))
        api(project(":index"))
    }
}

project(":execution") {
    dependencies {
        api(project(":common"))
        api(project(":planner"))
        api(project(":storage"))
        api(project(":index"))
        api(project(":txn"))
    }
}

project(":wal") {
    dependencies {
        api(project(":common"))
        api(project(":catalog"))
        api(project(":storage"))
        api(project(":txn"))
    }
}

project(":engine-api") {
    dependencies {
        api(project(":common"))
        api(project(":planner"))
        api(project(":execution"))
        api(project(":catalog"))
        api(project(":storage"))
        api(project(":txn"))
        api(project(":wal"))
        api(project(":index"))
        implementation(project(":sql-frontend"))
    }
}

project(":server") {
    apply(plugin = "application")
    dependencies {
        implementation(project(":engine-api"))
    }
    the<JavaApplication>().mainClass.set("dev.daisybase.server.ServerRuntime")
}

project(":cli") {
    apply(plugin = "application")
    dependencies {
        implementation(project(":engine-api"))
    }
    the<JavaApplication>().mainClass.set("dev.daisybase.cli.CliMain")
}

project(":testkit") {
    dependencies {
        api(project(":engine-api"))
        api(project(":common"))
    }
}

project(":bench") {
    dependencies {
        implementation(project(":engine-api"))
        implementation(project(":sql-frontend"))
        implementation("org.openjdk.jmh:jmh-core:1.37")
        annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    }
}

tasks.register<TestReport>("aggregateTestReport") {
    destinationDirectory.set(layout.buildDirectory.dir("reports/tests/aggregate"))
    testResults.from(subprojects.map { it.layout.buildDirectory.dir("test-results/test") })
}
