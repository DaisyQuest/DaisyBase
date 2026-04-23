import groovy.json.JsonOutput
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions

data class ApiDocsModule(
    val name: String,
    val responsibility: String,
    val docsPath: String,
    val sourcePath: String = name
)

data class ApiDocsType(
    val name: String,
    val kind: String,
    val packageName: String,
    val relativePath: String,
    val summary: String
)

fun String.htmlEscape(): String = buildString(length) {
    for (character in this@htmlEscape) {
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character.toString()
            }
        )
    }
}

fun normalizeJavadoc(comment: String?): String? {
    if (comment.isNullOrBlank()) {
        return null
    }
    val cleaned = comment
        .removePrefix("/**")
        .removeSuffix("*/")
        .lines()
        .map { line -> line.trim().removePrefix("*").trim() }
        .filter { line -> line.isNotBlank() && !line.startsWith("@") }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return cleaned.ifBlank { null }
}

fun firstSentence(comment: String?): String? {
    val normalized = normalizeJavadoc(comment) ?: return null
    return Regex("""^.*?[.!?](?=\s|$)""").find(normalized)?.value?.trim() ?: normalized
}

fun githubBlobUrl(path: String): String = "https://github.com/DaisyQuest/DaisyBase/blob/main/$path"

val apiDocsModules = listOf(
    ApiDocsModule("common", "Shared types, values, identifiers, and primitive contracts.", "docs/architecture/module-map.md"),
    ApiDocsModule("sql-frontend", "Parser, AST, diagnostics, and the PL/SQL bridge surface.", "docs/reference/sql-surface.md"),
    ApiDocsModule("catalog", "Durable metadata for schemas, auth, routines, sequences, and grants.", "docs/architecture/module-map.md"),
    ApiDocsModule("planner", "Binding, parameter inference, logical planning, and physical selection.", "docs/architecture/query-lifecycle.md"),
    ApiDocsModule("execution", "Runtime operators, DML, joins, aggregates, routines, and generated-key flows.", "docs/architecture/query-lifecycle.md"),
    ApiDocsModule("storage", "Paged heap persistence, overflow storage, page images, and recovery-facing state.", "docs/architecture/storage-recovery.md"),
    ApiDocsModule("txn", "Transaction lifecycle, snapshots, savepoints, and XA branch state.", "docs/reference/security-and-distributed-xa.md"),
    ApiDocsModule("wal", "Write-ahead log append, metadata, checkpoints, and restart primitives.", "docs/architecture/storage-recovery.md"),
    ApiDocsModule("index", "Index metadata and maintenance responsibilities.", "docs/architecture/module-map.md"),
    ApiDocsModule("engine-api", "Embedded runtime, transport, metadata, sequences, routines, and XA integration.", "docs/reference/runtime-surfaces.md"),
    ApiDocsModule("server", "Binary protocol server runtime.", "docs/reference/runtime-surfaces.md"),
    ApiDocsModule("cli", "Interactive shell and operator entry point.", "docs/reference/runtime-surfaces.md"),
    ApiDocsModule("jdbc", "JDBC driver, metadata, callable support, updatable result sets, and XA APIs.", "docs/reference/jdbc-surface.md"),
    ApiDocsModule("orm", "Annotation-based entity mapping, CRUD helpers, schema introspection, and source generation.", "docs/reference/orm-tooling.md"),
    ApiDocsModule("installer", "Core installer, demo installer, and packaging helpers.", "docs/getting-started/installers.md"),
    ApiDocsModule("demo-business-app", "TomEE enterprise demo using DaisyBase and the JDBC driver.", "docs/reference/demo-business-app.md"),
    ApiDocsModule("testkit", "Test utilities and reusable engine fixtures.", "docs/reference/testing-and-quality.md"),
    ApiDocsModule("bench", "Benchmark harness and performance workloads.", "docs/reference/testing-and-quality.md")
)

plugins {
    `java-library`
    jacoco
}

allprojects {
    group = "dev.daisybase"
    version = providers.gradleProperty("version").orElse("0.1.0-SNAPSHOT").get()

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
    "docs/site/javadoc-stylesheet.css",
    "docs/system/daisybase-system-catalog.json",
    "docs/mcp-description-system.md",
    "tools/daisybase-system-mcp/README.md",
    "tools/daisybase-system-mcp/server.py",
    "tools/daisybase-system-mcp/test_server.py",
    "tools/daisybase-orm-mcp/README.md",
    "tools/daisybase-orm-mcp/server.py",
    "tools/daisybase-orm-mcp/test_server.py",
    ".github/workflows/github-pages.yml",
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
    apply(plugin = "maven-publish")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
        withJavadocJar()
    }

    extensions.configure<PublishingExtension>("publishing") {
        publications {
            create<MavenPublication>("mavenJava") {
                artifactId = project.name
                from(components["java"])

                pom {
                    name.set("DaisyBase ${project.name}")
                    description.set("DaisyBase module ${project.path} for build-time integration.")
                    url.set("https://github.com/DaisyQuest/DaisyBase")
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/DaisyQuest/DaisyBase")
                credentials {
                    username = providers.gradleProperty("gpr.user")
                        .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                        .orElse(providers.environmentVariable("GITHUB_USERNAME"))
                        .orElse("not-set")
                        .get()
                    password = providers.gradleProperty("gpr.key")
                        .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                        .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
                        .orElse("not-set")
                        .get()
                }
            }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Javadoc>().configureEach {
        val standardOptions = options as StandardJavadocDocletOptions
        standardOptions.encoding = "UTF-8"
        standardOptions.charSet = "UTF-8"
        standardOptions.noTimestamp(true)
        standardOptions.windowTitle = "DaisyBase ${project.name} API"
        standardOptions.docTitle = "DaisyBase ${project.name} API"
        standardOptions.stylesheetFile = rootProject.file("docs/site/javadoc-stylesheet.css")
        standardOptions.addBooleanOption("html5", true)
        standardOptions.addStringOption("Xdoclint:none", "-quiet")
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
        testImplementation(project(":testkit"))
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

val apiAtlasOutputDir = layout.buildDirectory.dir("generated/docs-site/api")

tasks.register("generateApiAtlas") {
    description = "Generates a GitHub Pages API atlas for the DaisyBase public Java surface."
    group = "documentation"

    val sourceTrees = apiDocsModules.map { module ->
        fileTree(rootProject.file(module.sourcePath)) {
            include("src/main/java/**/*.java")
        }
    }
    inputs.files(sourceTrees)
    inputs.file(rootProject.file("docs/system/daisybase-system-catalog.json"))
    outputs.dir(apiAtlasOutputDir)

    doLast {
        val packageInfoPattern = Regex("""(?s)/\*\*(.*?)\*/\s*package\s+([A-Za-z0-9_.]+);""")
        val typePattern = Regex(
            """(?s)(/\*\*.*?\*/\s*)?(?:@[\w.]+\s*(?:\([^)]*\))?\s*)*public\s+(?:final\s+|abstract\s+|sealed\s+|non-sealed\s+)?(class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)\b"""
        )

        val modules = apiDocsModules.mapNotNull { module ->
            val srcDir = rootProject.file("${module.sourcePath}/src/main/java")
            if (!srcDir.exists()) {
                return@mapNotNull null
            }

            val packageSummaries = srcDir.walkTopDown()
                .filter { it.isFile && it.name == "package-info.java" }
                .mapNotNull { file ->
                    val match = packageInfoPattern.find(file.readText())
                    val packageName = match?.groups?.get(2)?.value ?: return@mapNotNull null
                    packageName to (firstSentence(match.groups[1]?.value) ?: module.responsibility)
                }
                .toMap()

            val types = srcDir.walkTopDown()
                .filter { it.isFile && it.extension == "java" && it.name != "package-info.java" }
                .mapNotNull { file ->
                    val text = file.readText()
                    val packageName = Regex("""^\s*package\s+([A-Za-z0-9_.]+);""", RegexOption.MULTILINE)
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                        ?: return@mapNotNull null
                    val match = typePattern.find(text) ?: return@mapNotNull null
                    val kind = match.groupValues[2]
                    val typeName = match.groupValues[3]
                    val summary = firstSentence(match.groups[1]?.value)
                        ?: packageSummaries[packageName]
                        ?: module.responsibility
                    ApiDocsType(
                        name = typeName,
                        kind = kind,
                        packageName = packageName,
                        relativePath = file.relativeTo(rootProject.projectDir).invariantSeparatorsPath,
                        summary = summary
                    )
                }
                .sortedWith(compareBy<ApiDocsType> { it.packageName }.thenBy { it.name })
                .toList()

            mapOf(
                "name" to module.name,
                "responsibility" to module.responsibility,
                "docsUrl" to githubBlobUrl(module.docsPath),
                "sourceUrl" to githubBlobUrl(module.sourcePath),
                "packageCount" to types.map { it.packageName }.distinct().size,
                "typeCount" to types.size,
                "packages" to types.groupBy { it.packageName }
                    .toSortedMap()
                    .map { (packageName, packageTypes) ->
                        mapOf(
                            "name" to packageName,
                            "summary" to (packageSummaries[packageName] ?: module.responsibility),
                            "typeCount" to packageTypes.size,
                            "types" to packageTypes.map { type ->
                                mapOf(
                                    "name" to type.name,
                                    "kind" to type.kind,
                                    "summary" to type.summary,
                                    "sourceUrl" to githubBlobUrl(type.relativePath),
                                    "javadocUrl" to "javadocs/${module.name}/${type.packageName.replace('.', '/')}/${type.name}.html"
                                )
                            }
                        )
                    }
            )
        }

        val totalPackages = modules.sumOf { (it["packageCount"] as Int) }
        val totalTypes = modules.sumOf { (it["typeCount"] as Int) }
        val outputDir = apiAtlasOutputDir.get().asFile.apply { mkdirs() }
        val html = buildString {
            appendLine("<!doctype html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("  <meta charset=\"utf-8\">")
            appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            appendLine("  <title>DaisyBase API Atlas</title>")
            appendLine("  <meta name=\"description\" content=\"GitHub Pages API atlas for DaisyBase public Java modules, packages, and types.\">")
            appendLine("  <link rel=\"stylesheet\" href=\"../styles.css\">")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("  <a class=\"skip-link\" href=\"#main-content\">Skip to main content</a>")
            appendLine("  <header class=\"site-header hero-shell\">")
            appendLine("    <p class=\"eyebrow\">DaisyBase GitHub Pages</p>")
            appendLine("    <h1>Complete API Atlas</h1>")
            appendLine("    <p class=\"lede\">Every public Java entry point that DaisyBase ships today, organized by module, package, and generated Javadoc so the product surface is browseable instead of buried.</p>")
            appendLine("    <div class=\"cta-row\">")
            appendLine("      <a class=\"button-link\" href=\"../index.html\">Documentation Portal</a>")
            appendLine("      <a class=\"button-link button-link-secondary\" href=\"catalog.json\">Machine-readable catalog</a>")
            appendLine("    </div>")
            appendLine("    <div class=\"stats-grid\">")
            appendLine("      <article class=\"stat-card\"><span class=\"stat-value\">${modules.size}</span><span class=\"stat-label\">Modules</span></article>")
            appendLine("      <article class=\"stat-card\"><span class=\"stat-value\">$totalPackages</span><span class=\"stat-label\">Packages</span></article>")
            appendLine("      <article class=\"stat-card\"><span class=\"stat-value\">$totalTypes</span><span class=\"stat-label\">Public types</span></article>")
            appendLine("    </div>")
            appendLine("  </header>")
            appendLine("  <main id=\"main-content\">")
            appendLine("    <section class=\"panel\">")
            appendLine("      <h2>Jump to a module</h2>")
            appendLine("      <div class=\"pill-row\">")
            modules.forEach { module ->
                val moduleName = module["name"] as String
                appendLine("        <a class=\"pill\" href=\"#module-${moduleName.htmlEscape()}\">${moduleName.htmlEscape()}</a>")
            }
            appendLine("      </div>")
            appendLine("    </section>")
            appendLine("    <section class=\"panel\">")
            appendLine("      <h2>Surface design</h2>")
            appendLine("      <div class=\"surface-grid\">")
            appendLine("        <article class=\"surface-card\"><h3>Generated Javadocs</h3><p>Each Java module gets its own themed Javadoc bundle for type members, nested records, enums, and method-level details.</p></article>")
            appendLine("        <article class=\"surface-card\"><h3>Atlas overview</h3><p>The atlas adds package-by-package context, module responsibilities, and direct source links so the API is understandable before you dive into member lists.</p></article>")
            appendLine("        <article class=\"surface-card\"><h3>Repository guides</h3><p>Every module card links back to the narrative docs and architecture notes that explain where the API fits in the wider DaisyBase system.</p></article>")
            appendLine("      </div>")
            appendLine("    </section>")
            appendLine("    <section class=\"panel\">")
            appendLine("      <h2>Module index</h2>")
            appendLine("      <div class=\"module-grid\">")
            modules.forEach { module ->
                val moduleName = module["name"] as String
                val responsibility = (module["responsibility"] as String).htmlEscape()
                val docsUrl = module["docsUrl"] as String
                val sourceUrl = module["sourceUrl"] as String
                val packageCount = module["packageCount"] as Int
                val typeCount = module["typeCount"] as Int
                @Suppress("UNCHECKED_CAST")
                val packages = module["packages"] as List<Map<String, Any>>
                appendLine("        <article class=\"module-card\" id=\"module-${moduleName.htmlEscape()}\">")
                appendLine("          <div class=\"module-header\">")
                appendLine("            <div>")
                appendLine("              <p class=\"module-kicker\">Module</p>")
                appendLine("              <h3>${moduleName.htmlEscape()}</h3>")
                appendLine("              <p>$responsibility</p>")
                appendLine("            </div>")
                appendLine("            <div class=\"type-meta\"><span>$packageCount packages</span><span>$typeCount public types</span></div>")
                appendLine("          </div>")
                appendLine("          <p class=\"module-links\"><a href=\"${docsUrl.htmlEscape()}\">Narrative guide</a><a href=\"javadocs/${moduleName.htmlEscape()}/index.html\">Javadocs</a><a href=\"${sourceUrl.htmlEscape()}\">Source tree</a></p>")
                packages.forEach { pkg ->
                    val packageName = pkg["name"] as String
                    val packageSummary = (pkg["summary"] as String).htmlEscape()
                    @Suppress("UNCHECKED_CAST")
                    val types = pkg["types"] as List<Map<String, String>>
                    appendLine("          <section class=\"package-section\">")
                    appendLine("            <div class=\"package-heading\">")
                    appendLine("              <div>")
                    appendLine("                <h4>${packageName.htmlEscape()}</h4>")
                    appendLine("                <p>$packageSummary</p>")
                    appendLine("              </div>")
                    appendLine("              <span class=\"package-count\">${types.size} types</span>")
                    appendLine("            </div>")
                    appendLine("            <ul class=\"type-list\">")
                    types.forEach { type ->
                        appendLine("              <li class=\"type-item\">")
                        appendLine("                <div>")
                        appendLine("                  <span class=\"type-kind\">${type.getValue("kind").htmlEscape()}</span>")
                        appendLine("                  <a class=\"type-title\" href=\"${type.getValue("javadocUrl").htmlEscape()}\">${type.getValue("name").htmlEscape()}</a>")
                        appendLine("                  <p>${type.getValue("summary").htmlEscape()}</p>")
                        appendLine("                </div>")
                        appendLine("                <div class=\"type-links\"><a href=\"${type.getValue("javadocUrl").htmlEscape()}\">Javadocs</a><a href=\"${type.getValue("sourceUrl").htmlEscape()}\">Source</a></div>")
                        appendLine("              </li>")
                    }
                    appendLine("            </ul>")
                    appendLine("          </section>")
                }
                appendLine("        </article>")
            }
            appendLine("      </div>")
            appendLine("    </section>")
            appendLine("  </main>")
            appendLine("  <footer class=\"site-footer\">")
            appendLine("    <p>DaisyBase API Atlas for GitHub Pages. Browse the generated Javadocs for member-level detail or jump back to the documentation portal for architecture and operations guidance.</p>")
            appendLine("  </footer>")
            appendLine("</body>")
            appendLine("</html>")
        }

        outputDir.resolve("index.html").writeText(html)
        outputDir.resolve("catalog.json").writeText(
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    mapOf(
                        "product" to mapOf("name" to "DaisyBase"),
                        "modules" to modules
                    )
                )
            )
        )
    }
}

tasks.register<Sync>("githubPagesSite") {
    description = "Assembles the DaisyBase GitHub Pages documentation site."
    group = "documentation"

    val siteDir = layout.buildDirectory.dir("gh-pages")
    dependsOn("generateApiAtlas")
    into(siteDir)
    from("docs/site")
    from(apiAtlasOutputDir) {
        into("api")
    }

    subprojects.forEach { subproject ->
        subproject.plugins.withId("java") {
            val javadocTask = subproject.tasks.named<Javadoc>("javadoc")
            dependsOn(javadocTask)
            from(javadocTask.map { it.destinationDir!! }) {
                into("api/javadocs/${subproject.name}")
            }
        }
    }

    doLast {
        siteDir.get().file(".nojekyll").asFile.writeText("")
    }
}
