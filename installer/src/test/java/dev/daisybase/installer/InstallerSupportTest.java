package dev.daisybase.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallerSupportTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesTemurinUrlsForSupportedPlatforms() {
        InstallerSupport.TemurinDownload windows = InstallerSupport.TemurinDownload.latestJdk21(
                InstallerSupport.Platform.WINDOWS, InstallerSupport.Architecture.X64);
        InstallerSupport.TemurinDownload linux = InstallerSupport.TemurinDownload.latestJdk21(
                InstallerSupport.Platform.LINUX, InstallerSupport.Architecture.AARCH64);

        assertTrue(windows.url().contains("/windows/x64/jdk/"));
        assertTrue(linux.url().contains("/linux/aarch64/jdk/"));
    }

    @Test
    void interactiveWizardAcceptsDefaults() throws Exception {
        InstallerSupport.CommandLine commandLine = new InstallerSupport.CommandLine(
                tempDir, null, null, null, null, fakeDist("cli"), fakeDist("server"),
                null, null, null, null, false, false, false);
        InstallerSupport.InstallationPlan defaults = InstallerSupport.defaultPlan(commandLine);

        StringWriter output = new StringWriter();
        InstallerSupport.InstallationPlan plan = InstallerSupport.promptForPlan(defaults,
                new java.io.InputStreamReader(new ByteArrayInputStream("\n\n\n\n\n\n\n".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8),
                output);

        assertEquals(defaults.installDir(), plan.installDir());
        assertEquals(defaults.databaseHome(), plan.databaseHome());
        assertEquals(defaults.referenceParserMode(), plan.referenceParserMode());
    }

    @Test
    void nonInteractiveInstallFlowCreatesConfigLaunchersAndCopiesDistributions() throws Exception {
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot);
        Path cliDist = fakeDist("cli");
        Path serverDist = fakeDist("server");
        Path installDir = tempDir.resolve("install");
        Path databaseHome = tempDir.resolve("database");
        Path javaHome = tempDir.resolve("jdk");
        Path parserHome = tempDir.resolve("PLSQL-Parser");
        Files.createDirectories(javaHome);
        Files.createDirectories(parserHome);

        StringWriter output = new StringWriter();
        int exitCode = InstallerMain.run(new String[] {
                        "--repo-root", repoRoot.toString(),
                        "--install-dir", installDir.toString(),
                        "--database-home", databaseHome.toString(),
                        "--java-home", javaHome.toString(),
                        "--reference-parser-home", parserHome.toString(),
                        "--reference-parser-mode", "auto",
                        "--cli-dist", cliDist.toString(),
                        "--server-dist", serverDist.toString(),
                        "--port", "16432",
                        "--checkpoint-interval", "11",
                        "--strict-durability", "false",
                        "--non-interactive"
                },
                new java.io.InputStreamReader(new ByteArrayInputStream(new byte[0]), StandardCharsets.UTF_8),
                new java.io.PrintWriter(output, true));

        assertEquals(0, exitCode);
        assertTrue(Files.exists(installDir.resolve("cli").resolve("bin").resolve("cli")));
        assertTrue(Files.exists(installDir.resolve("server").resolve("bin").resolve("server")));
        assertTrue(Files.exists(installDir.resolve("config").resolve("daisybase.properties")));
        assertTrue(Files.exists(installDir.resolve("config").resolve("javadb.properties")));
        assertTrue(Files.readString(installDir.resolve("config").resolve("daisybase.properties")).contains("server.port=16432"));
        assertTrue(Files.readString(installDir.resolve("config").resolve("javadb.properties")).contains("server.port=16432"));
        assertTrue(Files.exists(installDir.resolve("daisybase-server.sh")));
        assertTrue(Files.exists(installDir.resolve("daisybase-cli.bat")));
        assertTrue(Files.readString(installDir.resolve("start-server.sh")).contains("--config"));
        assertTrue(Files.readString(installDir.resolve("run-cli.bat")).contains("javadb.properties"));
        assertTrue(Files.readString(installDir.resolve("README.txt")).contains("DaisyBase installation complete"));
        assertTrue(Files.exists(installDir.resolve("release").resolve("INSTALLATION-MANIFEST.properties")));
        assertTrue(Files.exists(installDir.resolve("release").resolve("SHA256SUMS")));
    }

    @Test
    void installRejectsIncompleteDistributions() throws Exception {
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot);
        Path brokenCli = tempDir.resolve("broken-cli");
        Files.createDirectories(brokenCli.resolve("bin"));
        Files.writeString(brokenCli.resolve("bin").resolve("cli"), "#!/usr/bin/env bash\n");
        Files.writeString(brokenCli.resolve("bin").resolve("cli.bat"), "@echo off\n");

        InstallerSupport.InstallationPlan plan = new InstallerSupport.InstallationPlan(
                repoRoot,
                tempDir.resolve("install"),
                tempDir.resolve("database"),
                null,
                null,
                brokenCli,
                fakeDist("server"),
                15432,
                8,
                true,
                "disabled");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> InstallerSupport.performInstall(plan));
        assertTrue(failure.getMessage().contains("missing lib/"));
    }

    private Path fakeDist(String name) throws Exception {
        Path root = tempDir.resolve(name);
        Files.createDirectories(root.resolve("bin"));
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("bin").resolve(name), "#!/usr/bin/env bash\n");
        Files.writeString(root.resolve("bin").resolve(name + ".bat"), "@echo off\n");
        Files.writeString(root.resolve("lib").resolve("placeholder.txt"), name);
        return root;
    }
}
