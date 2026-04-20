package dev.daisybase.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallerPromptAndOutputTest {
    @TempDir
    Path tempDir;

    @Test
    void promptBlankReferenceParserHomeDisablesAutoModeAndClearsOptionalHomes() throws Exception {
        InstallerSupport.InstallationPlan defaults = new InstallerSupport.InstallationPlan(
                tempDir.resolve("repo"),
                tempDir.resolve("install-default"),
                tempDir.resolve("db-default"),
                tempDir.resolve("jdk-default"),
                tempDir.resolve("PLSQL-Parser"),
                fakeDist("cli"),
                fakeDist("server"),
                15432,
                8,
                true,
                "auto");

        StringWriter output = new StringWriter();
        InstallerSupport.InstallationPlan plan = InstallerSupport.promptForPlan(
                defaults,
                new InputStreamReader(new ByteArrayInputStream(("""








                        """).getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8),
                output);

        assertEquals(defaults.installDir(), plan.installDir());
        assertEquals(defaults.databaseHome(), plan.databaseHome());
        assertNull(plan.javaHome());
        assertNull(plan.referenceParserHome());
        assertEquals("disabled", plan.referenceParserMode());
        assertTrue(output.toString().contains("Reference parser home"));
    }

    @Test
    void promptNormalizesTrimmedValuesAndLowercasesParserMode() throws Exception {
        Path installInput = tempDir.resolve("install-root").resolve("..").resolve("installed");
        Path databaseInput = tempDir.resolve("db-root").resolve("..").resolve("db-home");
        Path javaInput = tempDir.resolve("jdks").resolve("..").resolve("temurin-21");
        Path parserInput = tempDir.resolve("tools").resolve("..").resolve("parser-home");
        InstallerSupport.InstallationPlan defaults = new InstallerSupport.InstallationPlan(
                tempDir.resolve("repo"),
                tempDir.resolve("default-install"),
                tempDir.resolve("default-db"),
                null,
                null,
                fakeDist("cli"),
                fakeDist("server"),
                15432,
                8,
                true,
                "disabled");

        String prompts = String.join("\n",
                "  " + installInput + "  ",
                "  " + databaseInput + "  ",
                "  " + javaInput + "  ",
                "  " + parserInput + "  ",
                " 16432 ",
                " 12 ",
                " false ",
                " REQUIRED ") + "\n";

        InstallerSupport.InstallationPlan plan = InstallerSupport.promptForPlan(
                defaults,
                new InputStreamReader(new ByteArrayInputStream(prompts.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8),
                new StringWriter());

        assertEquals(installInput.toAbsolutePath().normalize(), plan.installDir());
        assertEquals(databaseInput.toAbsolutePath().normalize(), plan.databaseHome());
        assertEquals(javaInput.toAbsolutePath().normalize(), plan.javaHome());
        assertEquals(parserInput.toAbsolutePath().normalize(), plan.referenceParserHome());
        assertEquals(16432, plan.port());
        assertEquals(12, plan.checkpointInterval());
        assertFalse(plan.strictDurability());
        assertEquals("required", plan.referenceParserMode());
    }

    @Test
    void performInstallWritesConfigAndLaunchersWithProvidedHomes() throws Exception {
        Path installDir = tempDir.resolve("install");
        Path databaseHome = tempDir.resolve("state").resolve("db-home");
        Path javaHome = tempDir.resolve("jdk").resolve("temurin-21");
        Path parserHome = tempDir.resolve("parser").resolve("reference");
        Files.createDirectories(javaHome);
        Files.createDirectories(parserHome);
        InstallerSupport.InstallationPlan plan = new InstallerSupport.InstallationPlan(
                tempDir.resolve("repo"),
                installDir,
                databaseHome,
                javaHome,
                parserHome,
                fakeDist("cli"),
                fakeDist("server"),
                16432,
                11,
                false,
                "required");

        InstallerSupport.performInstall(plan);

        Path configFile = installDir.resolve("config").resolve("daisybase.properties");
        assertLinesMatch(
                java.util.List.of(
                        "database.home=" + propertyPath(databaseHome),
                        "server.port=16432",
                        "checkpoint.interval=11",
                        "strict.durability=false",
                        "reference.parser.mode=required",
                        "reference.parser.home=" + propertyPath(parserHome),
                        "java.home=" + propertyPath(javaHome)),
                Files.readAllLines(configFile));

        String shellLauncher = Files.readString(installDir.resolve("start-server.sh"));
        assertTrue(shellLauncher.contains("export JAVA_HOME=\"" + javaHome + "\""));
        assertTrue(shellLauncher.contains("DAISYBASE_HOME="));
        assertTrue(shellLauncher.contains("config/daisybase.properties"));
        assertTrue(shellLauncher.contains("config/javadb.properties"));

        String batchLauncher = Files.readString(installDir.resolve("run-cli.bat"));
        assertTrue(batchLauncher.contains("set \"JAVA_HOME=" + javaHome + "\""));
        assertTrue(batchLauncher.contains("set \"DAISYBASE_HOME=%~dp0\""));
        assertTrue(batchLauncher.contains("config\\daisybase.properties"));
        assertTrue(batchLauncher.contains("config\\javadb.properties"));
        assertTrue(Files.exists(installDir.resolve("config").resolve("javadb.properties")));
        assertTrue(Files.exists(installDir.resolve("daisybase-server.sh")));
        assertTrue(Files.exists(installDir.resolve("daisybase-cli.bat")));

        assertTrue(Files.exists(installDir.resolve("cli").resolve("bin").resolve("cli")));
        assertTrue(Files.exists(installDir.resolve("server").resolve("bin").resolve("server.bat")));

        String manifest = Files.readString(installDir.resolve("release").resolve("INSTALLATION-MANIFEST.properties"));
        assertTrue(manifest.contains("layout.version=1"));
        assertTrue(manifest.contains("server.port=16432"));
        assertTrue(manifest.contains("reference.parser.mode=required"));
        assertTrue(manifest.contains("checksums.file=release/SHA256SUMS"));

        String checksums = Files.readString(installDir.resolve("release").resolve("SHA256SUMS"));
        assertTrue(checksums.contains("README.txt"));
        assertTrue(checksums.contains("config/daisybase.properties"));
        assertTrue(checksums.contains("config/javadb.properties"));
        assertTrue(checksums.contains(sha256(installDir.resolve("README.txt")) + "  README.txt"));
        assertFalse(checksums.contains("release/SHA256SUMS"));
    }

    @Test
    void performInstallOmitsReferenceParserHomeAfterWizardClearsIt() throws Exception {
        InstallerSupport.InstallationPlan defaults = new InstallerSupport.InstallationPlan(
                tempDir.resolve("repo"),
                tempDir.resolve("install-target"),
                tempDir.resolve("db-target"),
                null,
                tempDir.resolve("PLSQL-Parser"),
                fakeDist("cli"),
                fakeDist("server"),
                15432,
                8,
                true,
                "auto");

        String prompts = String.join("\n",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "") + "\n";
        InstallerSupport.InstallationPlan plan = InstallerSupport.promptForPlan(
                defaults,
                new InputStreamReader(new ByteArrayInputStream(prompts.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8),
                new StringWriter());

        InstallerSupport.performInstall(plan);

        Path configFile = plan.installDir().resolve("config").resolve("daisybase.properties");
        String config = Files.readString(configFile);
        assertTrue(config.contains("reference.parser.mode=disabled"));
        assertFalse(config.contains("reference.parser.home="));

        String readme = Files.readString(plan.installDir().resolve("README.txt"));
        assertTrue(readme.contains("Reference parser mode:"));
        assertTrue(readme.contains("disabled"));

        assertNotNull(plan.installDir());
    }

    private Path fakeDist(String name) throws Exception {
        Path root = tempDir.resolve(name + "-" + System.nanoTime());
        Files.createDirectories(root.resolve("bin"));
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("bin").resolve(name), "#!/usr/bin/env bash\n");
        Files.writeString(root.resolve("bin").resolve(name + ".bat"), "@echo off\n");
        Files.writeString(root.resolve("lib").resolve("placeholder.txt"), name);
        return root;
    }

    private String propertyPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }
}
