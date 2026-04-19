import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    `java-library`
    jacoco
}

allprojects {
    group = "dev.javadb"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
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
    the<JavaApplication>().mainClass.set("dev.javadb.installer.InstallerMain")
}

project(":jdbc") {
    dependencies {
        api(project(":common"))
        api(project(":engine-api"))
        implementation(project(":catalog"))
        implementation(project(":server"))
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
                includes = listOf("dev.javadb.sql.SqlFrontend\$Parser")
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
                includes = listOf("dev.javadb.storage.HeapStorageManager")
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
                includes = listOf("dev.javadb.wal.Wal\$WalManager")
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
                includes = listOf("dev.javadb.engine.RemoteProtocol")
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
    the<JavaApplication>().mainClass.set("dev.javadb.server.ServerRuntime")
}

project(":cli") {
    apply(plugin = "application")
    dependencies {
        implementation(project(":engine-api"))
    }
    the<JavaApplication>().mainClass.set("dev.javadb.cli.CliMain")
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
