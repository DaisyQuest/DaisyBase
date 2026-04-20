package dev.daisybase.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoInstallerSupportTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultPlanDetectsDemoWarFromRepoLayout() throws Exception {
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot.resolve("demo-business-app").resolve("build").resolve("libs"));
        Files.createFile(repoRoot.resolve("settings.gradle.kts"));
        Path war = repoRoot.resolve("demo-business-app").resolve("build").resolve("libs")
                .resolve("daisybase-demo-business-0.1.0-SNAPSHOT.war");
        Files.writeString(war, "placeholder-war");

        DemoInstallerSupport.CommandLine commandLine = new DemoInstallerSupport.CommandLine(
                repoRoot, null, null, null, null, null, null, null, null,
                false, false, false, false
        );

        DemoInstallerSupport.InstallationPlan plan = DemoInstallerSupport.defaultPlan(commandLine);

        assertEquals(war.toAbsolutePath().normalize(), plan.demoWar());
        assertEquals(Path.of(System.getProperty("user.home")).resolve("daisybase-demo-business").toAbsolutePath().normalize(),
                plan.installDir());
        assertEquals(8080, plan.httpPort());
        assertEquals("daisybase-demo-business", plan.contextPath());
    }

    @Test
    void promptAndGuiInputAllowBlankHomesAndNormalizeValues() throws Exception {
        DemoInstallerSupport.InstallationPlan defaults = new DemoInstallerSupport.InstallationPlan(
                tempDir.resolve("repo"),
                tempDir.resolve("install-default"),
                tempDir.resolve("db-default"),
                tempDir.resolve("jdk-default"),
                tempDir.resolve("tomee-default"),
                tempDir.resolve("demo.war"),
                8080,
                "daisybase-demo-business",
                "Northwind Field Systems"
        );

        String prompts = String.join("\n",
                "  " + tempDir.resolve("install-root").resolve("..").resolve("installed") + "  ",
                "  " + tempDir.resolve("db-root").resolve("..").resolve("db-home") + "  ",
                "",
                "",
                "  " + tempDir.resolve("payload").resolve("demo.war") + "  ",
                " 18080 ",
                " /ops-demo/ ",
                "  Atlas Field Systems  ") + "\n";

        DemoInstallerSupport.InstallationPlan promptPlan = DemoInstallerSupport.promptForPlan(
                defaults,
                new InputStreamReader(new ByteArrayInputStream(prompts.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8),
                new StringWriter()
        );

        assertEquals(tempDir.resolve("installed").toAbsolutePath().normalize(), promptPlan.installDir());
        assertEquals(tempDir.resolve("db-home").toAbsolutePath().normalize(), promptPlan.databaseHome());
        assertNull(promptPlan.javaHome());
        assertNull(promptPlan.tomeeHome());
        assertEquals(tempDir.resolve("payload").resolve("demo.war").toAbsolutePath().normalize(), promptPlan.demoWar());
        assertEquals(18080, promptPlan.httpPort());
        assertEquals("ops-demo", promptPlan.contextPath());
        assertEquals("Atlas Field Systems", promptPlan.enterpriseName());

        DemoInstallerSupport.InstallationPlan guiPlan = DemoInstallerSupport.applyGuiInput(defaults,
                new DemoInstallerSupport.GuiInput(
                        " " + tempDir.resolve("gui-install") + " ",
                        " " + tempDir.resolve("gui-db") + " ",
                        "",
                        "",
                        " " + tempDir.resolve("payload").resolve("gui-demo.war") + " ",
                        "19090",
                        " /gui-demo/ ",
                        " GUI Enterprise "
                ));

        assertEquals(tempDir.resolve("gui-install").toAbsolutePath().normalize(), guiPlan.installDir());
        assertEquals(tempDir.resolve("gui-db").toAbsolutePath().normalize(), guiPlan.databaseHome());
        assertNull(guiPlan.javaHome());
        assertNull(guiPlan.tomeeHome());
        assertEquals("gui-demo", guiPlan.contextPath());
        assertEquals("GUI Enterprise", guiPlan.enterpriseName());
    }

    @Test
    void performInstallFromExistingHomesCreatesRunnableLayout() throws Exception {
        Path installDir = tempDir.resolve("install");
        Path databaseHome = tempDir.resolve("db-home");
        Path javaHome = fakeJavaHome();
        Path tomeeHome = fakeTomeeHome();
        Path war = tempDir.resolve("payload").resolve("demo-business.war");
        Files.createDirectories(war.getParent());
        Files.writeString(war, "fake-war");

        DemoInstallerSupport.InstallationPlan plan = new DemoInstallerSupport.InstallationPlan(
                tempDir.resolve("repo"),
                installDir,
                databaseHome,
                javaHome,
                tomeeHome,
                war,
                18080,
                "ops-demo",
                "Atlas Field Systems"
        );

        DemoInstallerSupport.performInstall(plan);

        assertTrue(Files.exists(installDir.resolve("tomee").resolve("webapps").resolve("ops-demo.war")));
        assertTrue(Files.exists(installDir.resolve("app").resolve("ops-demo.war")));
        assertTrue(Files.exists(installDir.resolve("start-demo.sh")));
        assertTrue(Files.exists(installDir.resolve("start-demo.bat")));
        assertTrue(Files.exists(installDir.resolve("tomee").resolve("bin").resolve("setenv.sh")));
        assertTrue(Files.exists(installDir.resolve("config").resolve("demo-business.properties")));
        assertTrue(Files.readString(installDir.resolve("config").resolve("demo-business.properties")).contains("demo.http.port=18080"));
        assertTrue(Files.readString(installDir.resolve("config").resolve("demo-business.properties")).contains("demo.shutdown.port=18085"));
        assertTrue(Files.readString(installDir.resolve("config").resolve("demo-business.properties")).contains("demo.context.path=ops-demo"));
        assertTrue(Files.readString(installDir.resolve("README.txt")).contains("http://localhost:18080/ops-demo/"));
        assertTrue(Files.readString(installDir.resolve("tomee").resolve("conf").resolve("server.xml")).contains("port=\"18080\""));
        assertTrue(Files.readString(installDir.resolve("tomee").resolve("conf").resolve("server.xml")).contains("<Server port=\"18085\""));
        assertTrue(Files.readString(installDir.resolve("tomee").resolve("bin").resolve("setenv.sh")).contains("JAVADB_DEMO_ENTERPRISE_NAME=\"Atlas Field Systems\""));
        assertTrue(Files.readString(installDir.resolve("start-demo.bat")).contains("startup.bat"));
        assertTrue(Files.exists(installDir.resolve("release").resolve("INSTALLATION-MANIFEST.properties")));
        assertTrue(Files.exists(installDir.resolve("release").resolve("SHA256SUMS")));
        assertFalse(Files.exists(installDir.resolve("tomee").resolve("webapps").resolve("ops-demo")));
    }

    @Test
    void shutdownPortTracksHttpPortDeterministically() {
        assertEquals(8085, DemoInstallerSupport.shutdownPort(8080));
        assertEquals(18086, DemoInstallerSupport.shutdownPort(18081));
    }

    private Path fakeJavaHome() throws Exception {
        Path javaHome = tempDir.resolve("jdk");
        Files.createDirectories(javaHome.resolve("bin"));
        Files.writeString(javaHome.resolve("bin").resolve("java"), "#!/usr/bin/env bash\n");
        Files.writeString(javaHome.resolve("bin").resolve("java.exe"), "");
        return javaHome;
    }

    private Path fakeTomeeHome() throws Exception {
        Path root = tempDir.resolve("apache-tomee-plus-10.1.4");
        Files.createDirectories(root.resolve("bin"));
        Files.createDirectories(root.resolve("conf"));
        Files.createDirectories(root.resolve("webapps"));
        Files.createDirectories(root.resolve("logs"));
        Files.createDirectories(root.resolve("temp"));
        Files.createDirectories(root.resolve("work"));
        Files.writeString(root.resolve("bin").resolve("startup.sh"), "#!/usr/bin/env bash\n");
        Files.writeString(root.resolve("bin").resolve("shutdown.sh"), "#!/usr/bin/env bash\n");
        Files.writeString(root.resolve("bin").resolve("catalina.sh"), "#!/usr/bin/env bash\n");
        Files.writeString(root.resolve("bin").resolve("startup.bat"), "@echo off\n");
        Files.writeString(root.resolve("bin").resolve("shutdown.bat"), "@echo off\n");
        Files.writeString(root.resolve("bin").resolve("catalina.bat"), "@echo off\n");
        Files.writeString(root.resolve("conf").resolve("server.xml"), """
                <Server port="8005" shutdown="SHUTDOWN">
                  <Service name="Catalina">
                    <Connector port="8080" protocol="HTTP/1.1"/>
                  </Service>
                </Server>
                """);
        return root;
    }
}
