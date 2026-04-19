package dev.javadb.installer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

final class InstallerSupport {
    private static final int DEFAULT_PORT = 15432;
    private static final int DEFAULT_CHECKPOINT_INTERVAL = 8;

    private InstallerSupport() {
    }

    enum Platform {
        WINDOWS("windows", "zip"),
        LINUX("linux", "tar.gz");

        private final String adoptiumName;
        private final String archiveType;

        Platform(String adoptiumName, String archiveType) {
            this.adoptiumName = adoptiumName;
            this.archiveType = archiveType;
        }

        String adoptiumName() {
            return adoptiumName;
        }

        String archiveType() {
            return archiveType;
        }

        static Platform current() {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (osName.contains("win")) {
                return WINDOWS;
            }
            return LINUX;
        }
    }

    enum Architecture {
        X64("x64"),
        AARCH64("aarch64");

        private final String adoptiumName;

        Architecture(String adoptiumName) {
            this.adoptiumName = adoptiumName;
        }

        String adoptiumName() {
            return adoptiumName;
        }

        static Architecture current() {
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                return AARCH64;
            }
            return X64;
        }
    }

    record TemurinDownload(Platform platform, Architecture architecture, String url) {
        static TemurinDownload latestJdk21(Platform platform, Architecture architecture) {
            String url = "https://api.adoptium.net/v3/binary/latest/21/ga/"
                    + platform.adoptiumName() + "/"
                    + architecture.adoptiumName() + "/jdk/hotspot/normal/eclipse?project=jdk";
            return new TemurinDownload(platform, architecture, url);
        }
    }

    record CommandLine(Path repoRoot, Path installDir, Path databaseHome, Path javaHome, Path referenceParserHome,
                       Path cliDist, Path serverDist, Integer port, Integer checkpointInterval, Boolean strictDurability,
                       String referenceParserMode, boolean nonInteractive, boolean acceptDefaults, boolean help) {
    }

    record InstallationPlan(Path repoRoot, Path installDir, Path databaseHome, Path javaHome, Path referenceParserHome,
                            Path cliDist, Path serverDist, int port, int checkpointInterval,
                            boolean strictDurability, String referenceParserMode) {
        InstallationPlan {
            Objects.requireNonNull(repoRoot, "repoRoot");
            Objects.requireNonNull(installDir, "installDir");
            Objects.requireNonNull(databaseHome, "databaseHome");
            Objects.requireNonNull(cliDist, "cliDist");
            Objects.requireNonNull(serverDist, "serverDist");
            referenceParserMode = referenceParserMode == null || referenceParserMode.isBlank()
                    ? "disabled"
                    : referenceParserMode.toLowerCase(Locale.ROOT);
        }
    }

    static CommandLine parseArgs(String[] args) {
        Path repoRoot = null;
        Path installDir = null;
        Path databaseHome = null;
        Path javaHome = null;
        Path referenceParserHome = null;
        Path cliDist = null;
        Path serverDist = null;
        Integer port = null;
        Integer checkpointInterval = null;
        Boolean strictDurability = null;
        String referenceParserMode = null;
        boolean nonInteractive = false;
        boolean acceptDefaults = false;
        boolean help = false;
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            switch (arg) {
                case "--help", "-h" -> help = true;
                case "--non-interactive" -> nonInteractive = true;
                case "--accept-defaults" -> acceptDefaults = true;
                case "--repo-root" -> repoRoot = Path.of(requireValue(args, ++index, arg));
                case "--install-dir" -> installDir = Path.of(requireValue(args, ++index, arg));
                case "--database-home" -> databaseHome = Path.of(requireValue(args, ++index, arg));
                case "--java-home" -> javaHome = Path.of(requireValue(args, ++index, arg));
                case "--reference-parser-home" -> referenceParserHome = Path.of(requireValue(args, ++index, arg));
                case "--cli-dist" -> cliDist = Path.of(requireValue(args, ++index, arg));
                case "--server-dist" -> serverDist = Path.of(requireValue(args, ++index, arg));
                case "--port" -> port = Integer.parseInt(requireValue(args, ++index, arg));
                case "--checkpoint-interval" -> checkpointInterval = Integer.parseInt(requireValue(args, ++index, arg));
                case "--strict-durability" -> strictDurability = Boolean.parseBoolean(requireValue(args, ++index, arg));
                case "--reference-parser-mode" -> referenceParserMode = requireValue(args, ++index, arg);
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        return new CommandLine(repoRoot, installDir, databaseHome, javaHome, referenceParserHome, cliDist, serverDist, port,
                checkpointInterval, strictDurability, referenceParserMode, nonInteractive, acceptDefaults, help);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    static InstallationPlan defaultPlan(CommandLine cli) {
        Path repoRoot = cli.repoRoot() == null ? findRepoRoot(Path.of("").toAbsolutePath()) : cli.repoRoot().toAbsolutePath().normalize();
        Path installDir = normalize(cli.installDir(), Path.of(System.getProperty("user.home")).resolve("javadb"));
        Path databaseHome = normalize(cli.databaseHome(), installDir.resolve("db-home"));
        Path referenceParserHome = cli.referenceParserHome() != null
                ? cli.referenceParserHome().toAbsolutePath().normalize()
                : detectReferenceParserHome(repoRoot);
        String referenceParserMode = cli.referenceParserMode() != null
                ? cli.referenceParserMode()
                : (referenceParserHome == null ? "disabled" : "auto");
        return new InstallationPlan(
                repoRoot,
                installDir,
                databaseHome,
                cli.javaHome() == null ? null : cli.javaHome().toAbsolutePath().normalize(),
                referenceParserHome,
                normalize(cli.cliDist(), repoRoot.resolve("cli").resolve("build").resolve("install").resolve("cli")),
                normalize(cli.serverDist(), repoRoot.resolve("server").resolve("build").resolve("install").resolve("server")),
                cli.port() == null ? DEFAULT_PORT : cli.port(),
                cli.checkpointInterval() == null ? DEFAULT_CHECKPOINT_INTERVAL : cli.checkpointInterval(),
                cli.strictDurability() == null || cli.strictDurability(),
                referenceParserMode
        );
    }

    private static Path normalize(Path value, Path defaultValue) {
        return (value == null ? defaultValue : value).toAbsolutePath().normalize();
    }

    static InstallationPlan promptForPlan(InstallationPlan defaults, Reader input, Writer output) throws IOException {
        BufferedReader reader = new BufferedReader(input);
        output.write("JavaDB installation wizard\n");
        output.write("==========================\n");
        output.flush();
        Path installDir = Path.of(prompt(reader, output, "Install directory", defaults.installDir().toString(), false)).toAbsolutePath().normalize();
        Path databaseHome = Path.of(prompt(reader, output, "Database home", defaults.databaseHome().toString(), false)).toAbsolutePath().normalize();
        String javaHomeText = prompt(reader, output, "JAVA_HOME (blank to use current PATH/default)", pathText(defaults.javaHome()), true);
        String parserHomeText = prompt(reader, output, "Reference parser home (blank to disable bridge)", pathText(defaults.referenceParserHome()), true);
        int port = Integer.parseInt(prompt(reader, output, "Server port", Integer.toString(defaults.port()), false));
        int checkpointInterval = Integer.parseInt(prompt(reader, output, "Checkpoint interval", Integer.toString(defaults.checkpointInterval()), false));
        boolean strictDurability = Boolean.parseBoolean(prompt(reader, output, "Strict durability (true/false)",
                Boolean.toString(defaults.strictDurability()), false));
        String parserMode = prompt(reader, output, "Reference parser mode (disabled/auto/required)",
                defaults.referenceParserMode(), false).toLowerCase(Locale.ROOT);
        if (parserHomeText.isBlank() && parserMode.equals("auto")) {
            parserMode = "disabled";
        }
        return new InstallationPlan(defaults.repoRoot(), installDir, databaseHome,
                javaHomeText.isBlank() ? null : Path.of(javaHomeText).toAbsolutePath().normalize(),
                parserHomeText.isBlank() ? null : Path.of(parserHomeText).toAbsolutePath().normalize(),
                defaults.cliDist(), defaults.serverDist(), port, checkpointInterval, strictDurability, parserMode);
    }

    private static String pathText(Path path) {
        return path == null ? "" : path.toString();
    }

    private static String prompt(BufferedReader reader, Writer output, String label, String defaultValue, boolean allowBlank) throws IOException {
        output.write(label + " [" + defaultValue + "]: ");
        output.flush();
        String line = reader.readLine();
        if (line == null) {
            return defaultValue;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return allowBlank ? "" : defaultValue;
        }
        return trimmed;
    }

    static void performInstall(InstallationPlan plan) throws IOException {
        verifyDistribution(plan.cliDist(), "cli");
        verifyDistribution(plan.serverDist(), "server");
        Files.createDirectories(plan.installDir());
        Files.createDirectories(plan.databaseHome());
        Path cliTarget = plan.installDir().resolve("cli");
        Path serverTarget = plan.installDir().resolve("server");
        replaceDirectory(plan.cliDist(), cliTarget);
        replaceDirectory(plan.serverDist(), serverTarget);
        Path configDir = plan.installDir().resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("javadb.properties"), renderConfig(plan));
        writeLauncher(plan.installDir().resolve("start-server.bat"), renderWindowsLauncher("server", plan));
        writeLauncher(plan.installDir().resolve("run-cli.bat"), renderWindowsLauncher("cli", plan));
        writeLauncher(plan.installDir().resolve("start-server.sh"), renderShellLauncher("server", plan));
        writeLauncher(plan.installDir().resolve("run-cli.sh"), renderShellLauncher("cli", plan));
        Files.writeString(plan.installDir().resolve("README.txt"), renderReadme(plan));
        writeReleaseArtifacts(plan);
    }

    private static void verifyDistribution(Path path, String command) {
        if (!Files.isDirectory(path.resolve("bin"))) {
            throw new IllegalStateException(command + " distribution is missing bin/ at " + path);
        }
        if (!Files.isDirectory(path.resolve("lib"))) {
            throw new IllegalStateException(command + " distribution is missing lib/ at " + path);
        }
        if (!Files.exists(path.resolve("bin").resolve(command))) {
            throw new IllegalStateException(command + " distribution is missing launcher " + command + " at " + path);
        }
        if (!Files.exists(path.resolve("bin").resolve(command + ".bat"))) {
            throw new IllegalStateException(command + " distribution is missing launcher " + command + ".bat at " + path);
        }
        try (Stream<Path> files = Files.list(path.resolve("lib"))) {
            if (files.findAny().isEmpty()) {
                throw new IllegalStateException(command + " distribution has an empty lib/ directory at " + path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to inspect " + command + " distribution at " + path, exception);
        }
    }

    private static void writeLauncher(Path path, String content) throws IOException {
        Files.writeString(path, content);
        if (path.toString().endsWith(".sh")) {
            try {
                Files.setPosixFilePermissions(path, EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
            }
        }
    }

    private static void replaceDirectory(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            deleteRecursively(target);
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String renderConfig(InstallationPlan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("database.home=").append(encodePropertyPath(plan.databaseHome())).append('\n');
        builder.append("server.port=").append(plan.port()).append('\n');
        builder.append("checkpoint.interval=").append(plan.checkpointInterval()).append('\n');
        builder.append("strict.durability=").append(plan.strictDurability()).append('\n');
        builder.append("reference.parser.mode=").append(plan.referenceParserMode()).append('\n');
        if (plan.referenceParserHome() != null) {
            builder.append("reference.parser.home=").append(encodePropertyPath(plan.referenceParserHome())).append('\n');
        }
        if (plan.javaHome() != null) {
            builder.append("java.home=").append(encodePropertyPath(plan.javaHome())).append('\n');
        }
        return builder.toString();
    }

    private static void writeReleaseArtifacts(InstallationPlan plan) throws IOException {
        Path releaseDir = plan.installDir().resolve("release");
        Files.createDirectories(releaseDir);
        Files.writeString(releaseDir.resolve("INSTALLATION-MANIFEST.properties"), renderInstallationManifest(plan));
        Files.writeString(releaseDir.resolve("SHA256SUMS"), renderChecksums(plan.installDir()));
    }

    private static String encodePropertyPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String renderWindowsLauncher(String kind, InstallationPlan plan) {
        String command = kind.equals("server") ? "server" : "cli";
        StringBuilder builder = new StringBuilder();
        builder.append("@echo off\n");
        builder.append("setlocal\n");
        builder.append("set \"JAVADB_HOME=%~dp0\"\n");
        if (plan.javaHome() != null) {
            builder.append("set \"JAVA_HOME=").append(plan.javaHome()).append("\"\n");
        }
        builder.append("call \"%JAVADB_HOME%").append(command).append("\\bin\\").append(command)
                .append(".bat\" --config \"%JAVADB_HOME%config\\javadb.properties\"\n");
        builder.append("endlocal\n");
        return builder.toString();
    }

    private static String renderShellLauncher(String kind, InstallationPlan plan) {
        String command = kind.equals("server") ? "server" : "cli";
        StringBuilder builder = new StringBuilder();
        builder.append("#!/usr/bin/env bash\n");
        builder.append("set -euo pipefail\n");
        builder.append("JAVADB_HOME=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\n");
        if (plan.javaHome() != null) {
            builder.append("export JAVA_HOME=\"").append(plan.javaHome()).append("\"\n");
        }
        builder.append("exec \"$JAVADB_HOME/").append(command).append("/bin/").append(command)
                .append("\" --config \"$JAVADB_HOME/config/javadb.properties\"\n");
        return builder.toString();
    }

    private static String renderReadme(InstallationPlan plan) {
        return """
                JavaDB installation complete.

                Launchers:
                  - start-server.bat / start-server.sh
                  - run-cli.bat / run-cli.sh

                Editable configuration:
                  config/javadb.properties

                Release metadata:
                  release/INSTALLATION-MANIFEST.properties
                  release/SHA256SUMS

                Database home:
                  %s

                Reference parser mode:
                  %s
                """.formatted(plan.databaseHome(), plan.referenceParserMode());
    }

    private static String renderInstallationManifest(InstallationPlan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("layout.version=1\n");
        builder.append("installer.version=").append(runtimeVersion()).append('\n');
        builder.append("install.dir=").append(encodePropertyPath(plan.installDir())).append('\n');
        builder.append("database.home=").append(encodePropertyPath(plan.databaseHome())).append('\n');
        builder.append("cli.distribution=").append(encodePropertyPath(plan.cliDist())).append('\n');
        builder.append("server.distribution=").append(encodePropertyPath(plan.serverDist())).append('\n');
        builder.append("server.port=").append(plan.port()).append('\n');
        builder.append("checkpoint.interval=").append(plan.checkpointInterval()).append('\n');
        builder.append("strict.durability=").append(plan.strictDurability()).append('\n');
        builder.append("reference.parser.mode=").append(plan.referenceParserMode()).append('\n');
        if (plan.referenceParserHome() != null) {
            builder.append("reference.parser.home=").append(encodePropertyPath(plan.referenceParserHome())).append('\n');
        }
        if (plan.javaHome() != null) {
            builder.append("java.home=").append(encodePropertyPath(plan.javaHome())).append('\n');
        }
        builder.append("checksums.file=release/SHA256SUMS\n");
        return builder.toString();
    }

    private static String renderChecksums(Path installDir) throws IOException {
        Path releaseDir = installDir.resolve("release");
        StringBuilder builder = new StringBuilder();
        try (Stream<Path> files = Files.walk(installDir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(releaseDir))
                    .sorted(Comparator.comparing(path -> installDir.relativize(path).toString()))
                    .forEach(path -> builder.append(sha256(path))
                            .append("  ")
                            .append(installDir.relativize(path).toString().replace('\\', '/'))
                            .append('\n'));
        }
        return builder.toString();
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return toHex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to hash " + path, exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Missing SHA-256 support", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    private static String runtimeVersion() {
        Package installerPackage = InstallerMain.class.getPackage();
        String implementationVersion = installerPackage == null ? null : installerPackage.getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank() ? "dev" : implementationVersion;
    }

    static Path detectReferenceParserHome(Path repoRoot) {
        Path sibling = repoRoot.resolveSibling("PLSQL-Parser");
        if (Files.isDirectory(sibling)) {
            return sibling.toAbsolutePath().normalize();
        }
        Path child = repoRoot.resolve("PLSQL-Parser");
        if (Files.isDirectory(child)) {
            return child.toAbsolutePath().normalize();
        }
        return null;
    }

    static Path findRepoRoot(Path start) {
        for (Path cursor = start; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor.resolve("settings.gradle.kts"))) {
                return cursor.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("Failed to locate repository root from " + start);
    }
}
