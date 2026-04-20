package dev.daisybase.installer;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class DemoInstallerSupport {
    static final String PROFILE_NAME = "demo-business";
    static final String TOMEE_VERSION = "10.1.4";
    static final int DEFAULT_HTTP_PORT = 8080;
    static final int DEFAULT_SHUTDOWN_PORT_OFFSET = 5;
    static final String DEFAULT_CONTEXT_PATH = "daisybase-demo-business";
    static final String DEFAULT_ENTERPRISE_NAME = "Northwind Field Systems";

    private DemoInstallerSupport() {
    }

    record TomeeDownload(InstallerSupport.Platform platform, String version, String url) {
        static TomeeDownload latestPlus(InstallerSupport.Platform platform) {
            String suffix = platform == InstallerSupport.Platform.WINDOWS ? "zip" : "tar.gz";
            String url = "https://archive.apache.org/dist/tomee/tomee-" + TOMEE_VERSION
                    + "/apache-tomee-" + TOMEE_VERSION + "-plus." + suffix;
            return new TomeeDownload(platform, TOMEE_VERSION, url);
        }
    }

    record CommandLine(Path repoRoot, Path installDir, Path databaseHome, Path javaHome, Path tomeeHome, Path demoWar,
                       Integer httpPort, String contextPath, String enterpriseName,
                       boolean gui, boolean nonInteractive, boolean acceptDefaults, boolean help) {
    }

    record InstallationPlan(Path repoRoot, Path installDir, Path databaseHome, Path javaHome, Path tomeeHome, Path demoWar,
                            int httpPort, String contextPath, String enterpriseName) {
        InstallationPlan {
            Objects.requireNonNull(installDir, "installDir");
            Objects.requireNonNull(databaseHome, "databaseHome");
            Objects.requireNonNull(contextPath, "contextPath");
            Objects.requireNonNull(enterpriseName, "enterpriseName");
        }
    }

    record GuiInput(String installDir, String databaseHome, String javaHome, String tomeeHome, String demoWar,
                    String httpPort, String contextPath, String enterpriseName) {
    }

    static CommandLine parseArgs(String[] args) {
        Path repoRoot = null;
        Path installDir = null;
        Path databaseHome = null;
        Path javaHome = null;
        Path tomeeHome = null;
        Path demoWar = null;
        Integer httpPort = null;
        String contextPath = null;
        String enterpriseName = null;
        boolean gui = false;
        boolean nonInteractive = false;
        boolean acceptDefaults = false;
        boolean help = false;

        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            switch (arg) {
                case "--profile" -> {
                    String value = requireValue(args, ++index, arg);
                    if (!PROFILE_NAME.equalsIgnoreCase(value)) {
                        throw new IllegalArgumentException("Unsupported profile: " + value);
                    }
                }
                case "--help", "-h" -> help = true;
                case "--gui" -> gui = true;
                case "--non-interactive" -> nonInteractive = true;
                case "--accept-defaults" -> acceptDefaults = true;
                case "--repo-root" -> repoRoot = Path.of(requireValue(args, ++index, arg));
                case "--install-dir" -> installDir = Path.of(requireValue(args, ++index, arg));
                case "--database-home" -> databaseHome = Path.of(requireValue(args, ++index, arg));
                case "--java-home" -> javaHome = Path.of(requireValue(args, ++index, arg));
                case "--tomee-home" -> tomeeHome = Path.of(requireValue(args, ++index, arg));
                case "--demo-war" -> demoWar = Path.of(requireValue(args, ++index, arg));
                case "--http-port" -> httpPort = Integer.parseInt(requireValue(args, ++index, arg));
                case "--context-path" -> contextPath = requireValue(args, ++index, arg);
                case "--enterprise-name" -> enterpriseName = requireValue(args, ++index, arg);
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }

        return new CommandLine(repoRoot, installDir, databaseHome, javaHome, tomeeHome, demoWar, httpPort,
                contextPath, enterpriseName, gui, nonInteractive, acceptDefaults, help);
    }

    static InstallationPlan defaultPlan(CommandLine commandLine) {
        Path repoRoot = commandLine.repoRoot() == null ? detectRepoRoot() : commandLine.repoRoot().toAbsolutePath().normalize();
        Path installDir = normalize(commandLine.installDir(), Path.of(System.getProperty("user.home")).resolve("daisybase-demo-business"));
        Path databaseHome = normalize(commandLine.databaseHome(), installDir.resolve("data").resolve("db-home"));
        Path demoWar = commandLine.demoWar() == null ? detectDemoWar(repoRoot) : commandLine.demoWar().toAbsolutePath().normalize();
        String contextPath = normalizeContextPath(commandLine.contextPath() == null ? DEFAULT_CONTEXT_PATH : commandLine.contextPath());
        String enterpriseName = commandLine.enterpriseName() == null || commandLine.enterpriseName().isBlank()
                ? DEFAULT_ENTERPRISE_NAME
                : commandLine.enterpriseName().trim();
        return new InstallationPlan(
                repoRoot,
                installDir,
                databaseHome,
                commandLine.javaHome() == null ? null : commandLine.javaHome().toAbsolutePath().normalize(),
                commandLine.tomeeHome() == null ? null : commandLine.tomeeHome().toAbsolutePath().normalize(),
                demoWar,
                commandLine.httpPort() == null ? DEFAULT_HTTP_PORT : commandLine.httpPort(),
                contextPath,
                enterpriseName
        );
    }

    static InstallationPlan promptForPlan(InstallationPlan defaults, Reader input, Writer output) throws IOException {
        BufferedReader reader = new BufferedReader(input);
        output.write("DaisyBase Demo Business installer\n");
        output.write("==============================\n");
        output.flush();
        return applyGuiInput(defaults, new GuiInput(
                prompt(reader, output, "Install directory", defaults.installDir().toString(), false),
                prompt(reader, output, "Database home", defaults.databaseHome().toString(), false),
                prompt(reader, output, "JAVA_HOME (blank to bundle Temurin JDK 21)", pathText(defaults.javaHome()), true),
                prompt(reader, output, "TomEE home (blank to download TomEE Plus " + TOMEE_VERSION + ")", pathText(defaults.tomeeHome()), true),
                prompt(reader, output, "Demo WAR", pathText(defaults.demoWar()), false),
                prompt(reader, output, "HTTP port", Integer.toString(defaults.httpPort()), false),
                prompt(reader, output, "Context path", defaults.contextPath(), false),
                prompt(reader, output, "Enterprise name", defaults.enterpriseName(), false)
        ));
    }

    static InstallationPlan promptForPlanGui(InstallationPlan defaults) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("No graphical environment is available.");
        }
        AtomicReference<InstallationPlan> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Runnable task = () -> {
            JDialog dialog = new JDialog((JFrame) null, "DaisyBase Demo Business Installer", true);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setLayout(new BorderLayout(12, 12));

            JTextField installDirField = new JTextField(defaults.installDir().toString(), 40);
            JTextField databaseHomeField = new JTextField(defaults.databaseHome().toString(), 40);
            JTextField javaHomeField = new JTextField(pathText(defaults.javaHome()), 40);
            JTextField tomeeHomeField = new JTextField(pathText(defaults.tomeeHome()), 40);
            JTextField demoWarField = new JTextField(pathText(defaults.demoWar()), 40);
            JTextField httpPortField = new JTextField(Integer.toString(defaults.httpPort()), 12);
            JTextField contextPathField = new JTextField(defaults.contextPath(), 24);
            JTextField enterpriseNameField = new JTextField(defaults.enterpriseName(), 40);

            JPanel fields = new JPanel();
            fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
            fields.add(row("Install directory", installDirField, true, dialog));
            fields.add(row("Database home", databaseHomeField, true, dialog));
            fields.add(row("JAVA_HOME", javaHomeField, true, dialog));
            fields.add(row("TomEE home", tomeeHomeField, true, dialog));
            fields.add(row("Demo WAR", demoWarField, false, dialog));
            fields.add(row("HTTP port", httpPortField, null, dialog));
            fields.add(row("Context path", contextPathField, null, dialog));
            fields.add(row("Enterprise name", enterpriseNameField, null, dialog));

            JCheckBox bundleJdkNote = new JCheckBox("Blank JAVA_HOME downloads Temurin JDK 21 into the install tree", true);
            bundleJdkNote.setEnabled(false);
            JCheckBox downloadTomeeNote = new JCheckBox("Blank TomEE home downloads Apache TomEE Plus " + TOMEE_VERSION, true);
            downloadTomeeNote.setEnabled(false);
            fields.add(bundleJdkNote);
            fields.add(downloadTomeeNote);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton cancel = new JButton("Cancel");
            JButton install = new JButton("Install");
            actions.add(cancel);
            actions.add(install);

            cancel.addActionListener(event -> {
                result.set(null);
                dialog.dispose();
            });
            install.addActionListener(event -> {
                try {
                    result.set(applyGuiInput(defaults, new GuiInput(
                            installDirField.getText(),
                            databaseHomeField.getText(),
                            javaHomeField.getText(),
                            tomeeHomeField.getText(),
                            demoWarField.getText(),
                            httpPortField.getText(),
                            contextPathField.getText(),
                            enterpriseNameField.getText()
                    )));
                    dialog.dispose();
                } catch (RuntimeException exception) {
                    failure.set(exception);
                    JOptionPane.showMessageDialog(dialog, exception.getMessage(), "Invalid configuration",
                            JOptionPane.ERROR_MESSAGE);
                }
            });

            dialog.add(fields, BorderLayout.CENTER);
            dialog.add(actions, BorderLayout.SOUTH);
            dialog.pack();
            dialog.setMinimumSize(new Dimension(860, 420));
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                task.run();
            } else {
                SwingUtilities.invokeAndWait(task);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to open installer UI", exception);
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    static InstallationPlan applyGuiInput(InstallationPlan defaults, GuiInput input) {
        Path installDir = normalizeRequired(input.installDir(), "Install directory");
        Path databaseHome = normalizeRequired(input.databaseHome(), "Database home");
        Path javaHome = normalizeOptional(input.javaHome());
        Path tomeeHome = normalizeOptional(input.tomeeHome());
        Path demoWar = normalizeRequired(input.demoWar(), "Demo WAR");
        int httpPort;
        try {
            httpPort = Integer.parseInt(requireText(input.httpPort(), "HTTP port"));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("HTTP port must be an integer.");
        }
        if (httpPort < 1 || httpPort > 65535) {
            throw new IllegalArgumentException("HTTP port must be between 1 and 65535.");
        }
        String contextPath = normalizeContextPath(requireText(input.contextPath(), "Context path"));
        String enterpriseName = requireText(input.enterpriseName(), "Enterprise name");
        return new InstallationPlan(defaults.repoRoot(), installDir, databaseHome, javaHome, tomeeHome, demoWar,
                httpPort, contextPath, enterpriseName);
    }

    static void performInstall(InstallationPlan plan) throws IOException {
        verifyWar(plan.demoWar());
        Files.createDirectories(plan.installDir());
        Files.createDirectories(plan.databaseHome());

        Path runtimeHome = installJavaRuntime(plan);
        Path tomeeInstallDir = plan.installDir().resolve("tomee");
        installTomee(plan, tomeeInstallDir);
        configureTomee(plan, runtimeHome, tomeeInstallDir);

        Files.createDirectories(plan.installDir().resolve("config"));
        Files.writeString(plan.installDir().resolve("config").resolve("demo-business.properties"), renderConfig(plan, runtimeHome, tomeeInstallDir));
        writeLauncher(plan.installDir().resolve("start-demo.bat"), renderWindowsLauncher("startup", plan, runtimeHome, tomeeInstallDir));
        writeLauncher(plan.installDir().resolve("stop-demo.bat"), renderWindowsLauncher("shutdown", plan, runtimeHome, tomeeInstallDir));
        writeLauncher(plan.installDir().resolve("run-demo-foreground.bat"), renderWindowsLauncher("run", plan, runtimeHome, tomeeInstallDir));
        writeLauncher(plan.installDir().resolve("start-demo.sh"), renderShellLauncher("startup", plan, runtimeHome, tomeeInstallDir));
        writeLauncher(plan.installDir().resolve("stop-demo.sh"), renderShellLauncher("shutdown", plan, runtimeHome, tomeeInstallDir));
        writeLauncher(plan.installDir().resolve("run-demo-foreground.sh"), renderShellLauncher("run", plan, runtimeHome, tomeeInstallDir));
        Files.writeString(plan.installDir().resolve("README.txt"), renderReadme(plan, runtimeHome, tomeeInstallDir));
        writeReleaseArtifacts(plan, runtimeHome, tomeeInstallDir);
    }

    static String applicationUrl(InstallationPlan plan) {
        return "http://localhost:" + plan.httpPort() + "/" + plan.contextPath() + "/";
    }

    static int shutdownPort(InstallationPlan plan) {
        return shutdownPort(plan.httpPort());
    }

    static int shutdownPort(int httpPort) {
        int candidate = httpPort + DEFAULT_SHUTDOWN_PORT_OFFSET;
        if (candidate >= 1 && candidate <= 65535 && candidate != httpPort) {
            return candidate;
        }
        candidate = httpPort - DEFAULT_SHUTDOWN_PORT_OFFSET;
        if (candidate >= 1 && candidate <= 65535 && candidate != httpPort) {
            return candidate;
        }
        throw new IllegalArgumentException("Cannot derive a valid TomEE shutdown port from HTTP port " + httpPort);
    }

    private static Path installJavaRuntime(InstallationPlan plan) throws IOException {
        if (plan.javaHome() != null) {
            if (!Files.isDirectory(plan.javaHome().resolve("bin"))) {
                throw new IllegalStateException("JAVA_HOME is missing bin/ at " + plan.javaHome());
            }
            return plan.javaHome();
        }
        InstallerSupport.Platform platform = InstallerSupport.Platform.current();
        InstallerSupport.Architecture architecture = InstallerSupport.Architecture.current();
        InstallerSupport.TemurinDownload download = InstallerSupport.TemurinDownload.latestJdk21(platform, architecture);
        Path cacheDir = plan.installDir().resolve(".cache");
        Files.createDirectories(cacheDir);
        String extension = platform == InstallerSupport.Platform.WINDOWS ? ".zip" : ".tar.gz";
        Path archive = cacheDir.resolve("temurin-jdk21-" + platform.name().toLowerCase(Locale.ROOT) + "-"
                + architecture.name().toLowerCase(Locale.ROOT) + extension);
        downloadIfMissing(download.url(), archive);
        Path runtimeRoot = plan.installDir().resolve("runtime");
        Files.createDirectories(runtimeRoot);
        Path target = runtimeRoot.resolve("jdk");
        if (Files.exists(target.resolve("bin"))) {
            return target;
        }
        extractArchiveTo(archive, target, platform);
        return target;
    }

    private static void installTomee(InstallationPlan plan, Path tomeeInstallDir) throws IOException {
        if (Files.exists(tomeeInstallDir)) {
            deleteRecursively(tomeeInstallDir);
        }
        if (plan.tomeeHome() != null) {
            if (!Files.isDirectory(plan.tomeeHome().resolve("bin"))
                    || !Files.isDirectory(plan.tomeeHome().resolve("conf"))
                    || !Files.isDirectory(plan.tomeeHome().resolve("webapps"))) {
                throw new IllegalStateException("TomEE home is not a valid installation: " + plan.tomeeHome());
            }
            copyDirectory(plan.tomeeHome(), tomeeInstallDir);
        } else {
            InstallerSupport.Platform platform = InstallerSupport.Platform.current();
            TomeeDownload download = TomeeDownload.latestPlus(platform);
            Path cacheDir = plan.installDir().resolve(".cache");
            Files.createDirectories(cacheDir);
            String extension = platform == InstallerSupport.Platform.WINDOWS ? ".zip" : ".tar.gz";
            Path archive = cacheDir.resolve("apache-tomee-" + download.version() + "-plus." + extension);
            downloadIfMissing(download.url(), archive);
            extractArchiveTo(archive, tomeeInstallDir, platform);
        }
        purgeTomEETransientDirectories(tomeeInstallDir);
    }

    private static void configureTomee(InstallationPlan plan, Path runtimeHome, Path tomeeInstallDir) throws IOException {
        Path serverXml = tomeeInstallDir.resolve("conf").resolve("server.xml");
        patchServerXml(serverXml, plan.httpPort(), shutdownPort(plan));
        Files.createDirectories(plan.installDir().resolve("app"));
        Path stagedWar = plan.installDir().resolve("app").resolve(plan.contextPath() + ".war");
        Files.copy(plan.demoWar(), stagedWar, StandardCopyOption.REPLACE_EXISTING);
        Path deployedWar = tomeeInstallDir.resolve("webapps").resolve(plan.contextPath() + ".war");
        Files.copy(plan.demoWar(), deployedWar, StandardCopyOption.REPLACE_EXISTING);
        Path exploded = tomeeInstallDir.resolve("webapps").resolve(plan.contextPath());
        if (Files.exists(exploded)) {
            deleteRecursively(exploded);
        }
        Files.writeString(tomeeInstallDir.resolve("bin").resolve("setenv.sh"), renderTomEeSetenvShell(plan, runtimeHome, tomeeInstallDir));
        Files.writeString(tomeeInstallDir.resolve("bin").resolve("setenv.bat"), renderTomEeSetenvBatch(plan, runtimeHome, tomeeInstallDir));
        try {
            Files.setPosixFilePermissions(tomeeInstallDir.resolve("bin").resolve("setenv.sh"), EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private static void verifyWar(Path demoWar) {
        if (demoWar == null || !Files.isRegularFile(demoWar)) {
            throw new IllegalStateException("Demo WAR is missing: " + demoWar);
        }
        if (!demoWar.getFileName().toString().endsWith(".war")) {
            throw new IllegalStateException("Demo WAR must be a .war file: " + demoWar);
        }
    }

    private static void patchServerXml(Path serverXml, int httpPort, int shutdownPort) throws IOException {
        try {
            var builder = DocumentBuilderFactory.newInstance();
            builder.setNamespaceAware(false);
            var document = builder.newDocumentBuilder().parse(serverXml.toFile());
            var servers = document.getElementsByTagName("Server");
            if (servers.getLength() == 0 || !(servers.item(0) instanceof org.w3c.dom.Element serverElement)) {
                throw new IllegalStateException("Failed to find the TomEE <Server> element in " + serverXml);
            }
            serverElement.setAttribute("port", Integer.toString(shutdownPort));
            var connectors = document.getElementsByTagName("Connector");
            boolean updated = false;
            for (int index = 0; index < connectors.getLength(); index++) {
                var node = connectors.item(index);
                if (!(node instanceof org.w3c.dom.Element element)) {
                    continue;
                }
                String port = element.getAttribute("port");
                String protocol = element.getAttribute("protocol");
                if ("8080".equals(port) || protocol.contains("HTTP")) {
                    element.setAttribute("port", Integer.toString(httpPort));
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                throw new IllegalStateException("Failed to find the TomEE HTTP connector in " + serverXml);
            }
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(new DOMSource(document), new StreamResult(serverXml.toFile()));
        } catch (Exception exception) {
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IllegalStateException("Failed to patch TomEE server.xml at " + serverXml, exception);
        }
    }

    private static void purgeTomEETransientDirectories(Path tomeeInstallDir) throws IOException {
        for (String child : List.of("logs", "temp", "work")) {
            Path dir = tomeeInstallDir.resolve(child);
            if (Files.exists(dir)) {
                deleteRecursively(dir);
            }
            Files.createDirectories(dir);
        }
    }

    private static void downloadIfMissing(String url, Path archive) throws IOException {
        if (Files.exists(archive) && Files.size(archive) > 0) {
            return;
        }
        Files.createDirectories(archive.getParent());
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        try {
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(archive));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Download failed with HTTP " + response.statusCode() + " for " + url);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, exception);
        }
    }

    private static void extractArchiveTo(Path archive, Path target, InstallerSupport.Platform platform) throws IOException {
        Path tempExtract = Files.createTempDirectory(target.getParent() == null ? Path.of(".") : target.getParent(), "extract-");
        try {
            if (platform == InstallerSupport.Platform.WINDOWS) {
                unzip(archive, tempExtract);
            } else {
                untar(archive, tempExtract);
            }
            Path extractedRoot = singleChildDirectory(tempExtract);
            if (extractedRoot == null) {
                Files.createDirectories(target);
                copyDirectory(tempExtract, target);
            } else {
                copyDirectory(extractedRoot, target);
            }
        } finally {
            deleteRecursively(tempExtract);
        }
    }

    private static void unzip(Path archive, Path destination) throws IOException {
        try (InputStream fileInput = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(fileInput)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path resolved = destination.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(destination)) {
                    throw new IllegalStateException("Zip entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zip, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    private static void untar(Path archive, Path destination) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("tar", "-xzf", archive.toAbsolutePath().toString(), "-C",
                destination.toAbsolutePath().toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new IllegalStateException("tar extraction failed: " + output);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while extracting " + archive, exception);
        }
    }

    private static Path singleChildDirectory(Path root) throws IOException {
        try (Stream<Path> children = Files.list(root)) {
            List<Path> items = children.toList();
            if (items.size() == 1 && Files.isDirectory(items.getFirst())) {
                return items.getFirst();
            }
            return null;
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
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
        if (root == null || !Files.exists(root)) {
            return;
        }
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

    private static String renderConfig(InstallationPlan plan, Path runtimeHome, Path tomeeInstallDir) {
        String jdbcUrl = demoJdbcUrl(plan);
        return """
                profile=demo-business
                install.dir=%s
                tomee.home=%s
                runtime.java.home=%s
                database.home=%s
                demo.jdbc.url=%s
                demo.http.port=%d
                demo.shutdown.port=%d
                demo.context.path=%s
                demo.enterprise.name=%s
                demo.url=%s
                """.formatted(
                encodePropertyPath(plan.installDir()),
                encodePropertyPath(tomeeInstallDir),
                encodePropertyPath(runtimeHome),
                encodePropertyPath(plan.databaseHome()),
                jdbcUrl.replace('\\', '/'),
                plan.httpPort(),
                shutdownPort(plan),
                plan.contextPath(),
                escapeProperty(plan.enterpriseName()),
                applicationUrl(plan)
        );
    }

    private static String renderTomEeSetenvShell(InstallationPlan plan, Path runtimeHome, Path tomeeInstallDir) {
        return """
                #!/usr/bin/env bash
                set -euo pipefail
                DEMO_INSTALL_HOME="$(cd "$(dirname "$0")/../.." && pwd)"
                export JAVA_HOME="%s"
                export JAVADB_DEMO_JDBC_URL="%s"
                export JAVADB_DEMO_ENTERPRISE_NAME="%s"
                export CATALINA_HOME="%s"
                export CATALINA_BASE="%s"
                """.formatted(
                runtimeHome,
                demoJdbcUrl(plan),
                shellEscape(plan.enterpriseName()),
                tomeeInstallDir,
                tomeeInstallDir
        );
    }

    private static String renderTomEeSetenvBatch(InstallationPlan plan, Path runtimeHome, Path tomeeInstallDir) {
        return """
                @echo off
                set "DEMO_INSTALL_HOME=%%~dp0..\\.."
                set "JAVA_HOME=%s"
                set "JAVADB_DEMO_JDBC_URL=%s"
                set "JAVADB_DEMO_ENTERPRISE_NAME=%s"
                set "CATALINA_HOME=%s"
                set "CATALINA_BASE=%s"
                """.formatted(
                runtimeHome,
                demoJdbcUrl(plan),
                plan.enterpriseName(),
                tomeeInstallDir,
                tomeeInstallDir
        );
    }

    private static String renderShellLauncher(String command, InstallationPlan plan, Path runtimeHome, Path tomeeInstallDir) {
        String target = switch (command) {
            case "startup" -> "startup.sh";
            case "shutdown" -> "shutdown.sh";
            default -> "catalina.sh run";
        };
        String exec = command.equals("run")
                ? "\"$DEMO_HOME/tomee/bin/catalina.sh\" run"
                : "\"$DEMO_HOME/tomee/bin/" + target + "\"";
        return """
                #!/usr/bin/env bash
                set -euo pipefail
                DEMO_HOME="$(cd "$(dirname "$0")" && pwd)"
                export JAVA_HOME="%s"
                export JAVADB_DEMO_JDBC_URL="%s"
                export JAVADB_DEMO_ENTERPRISE_NAME="%s"
                export CATALINA_HOME="%s"
                export CATALINA_BASE="%s"
                exec %s
                """.formatted(
                runtimeHome,
                demoJdbcUrl(plan),
                shellEscape(plan.enterpriseName()),
                tomeeInstallDir,
                tomeeInstallDir,
                exec
        );
    }

    private static String renderWindowsLauncher(String command, InstallationPlan plan, Path runtimeHome, Path tomeeInstallDir) {
        String call = switch (command) {
            case "startup" -> "call \"%DEMO_HOME%tomee\\bin\\startup.bat\"";
            case "shutdown" -> "call \"%DEMO_HOME%tomee\\bin\\shutdown.bat\"";
            default -> "call \"%DEMO_HOME%tomee\\bin\\catalina.bat\" run";
        };
        return """
                @echo off
                setlocal
                set "DEMO_HOME=%%~dp0"
                set "JAVA_HOME=%s"
                set "JAVADB_DEMO_JDBC_URL=%s"
                set "JAVADB_DEMO_ENTERPRISE_NAME=%s"
                set "CATALINA_HOME=%s"
                set "CATALINA_BASE=%s"
                %s
                endlocal
                """.formatted(
                runtimeHome,
                demoJdbcUrl(plan),
                plan.enterpriseName(),
                tomeeInstallDir,
                tomeeInstallDir,
                call
        );
    }

    private static String renderReadme(InstallationPlan plan, Path runtimeHome, Path tomeeInstallDir) {
        return """
                DaisyBase Demo Business installation complete.

                Install root:
                  %s

                Runtime:
                  %s

                TomEE:
                  %s

                Application URL:
                  %s

                TomEE ports:
                  HTTP: %d
                  Shutdown: %d

                Launchers:
                  start-demo.bat / start-demo.sh
                  stop-demo.bat / stop-demo.sh
                  run-demo-foreground.bat / run-demo-foreground.sh

                Demo configuration:
                  config/demo-business.properties

                Bundled application WAR:
                  app/%s.war
                """.formatted(
                plan.installDir(),
                runtimeHome,
                tomeeInstallDir,
                applicationUrl(plan),
                plan.httpPort(),
                shutdownPort(plan),
                plan.contextPath()
        );
    }

    private static void writeReleaseArtifacts(InstallationPlan plan, Path runtimeHome, Path tomeeInstallDir) throws IOException {
        Path releaseDir = plan.installDir().resolve("release");
        Files.createDirectories(releaseDir);
        Files.writeString(releaseDir.resolve("INSTALLATION-MANIFEST.properties"), renderManifest(plan, runtimeHome, tomeeInstallDir));
        Files.writeString(releaseDir.resolve("SHA256SUMS"), renderChecksums(plan.installDir()));
    }

    private static String renderManifest(InstallationPlan plan, Path runtimeHome, Path tomeeInstallDir) {
        return """
                layout.version=2
                installer.version=%s
                profile=demo-business
                install.dir=%s
                runtime.java.home=%s
                tomee.home=%s
                database.home=%s
                demo.war=%s
                demo.http.port=%d
                demo.shutdown.port=%d
                demo.context.path=%s
                demo.enterprise.name=%s
                demo.url=%s
                """.formatted(
                runtimeVersion(),
                encodePropertyPath(plan.installDir()),
                encodePropertyPath(runtimeHome),
                encodePropertyPath(tomeeInstallDir),
                encodePropertyPath(plan.databaseHome()),
                encodePropertyPath(plan.installDir().resolve("app").resolve(plan.contextPath() + ".war")),
                plan.httpPort(),
                shutdownPort(plan),
                plan.contextPath(),
                escapeProperty(plan.enterpriseName()),
                applicationUrl(plan)
        );
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
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to hash " + path, exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Missing SHA-256 support", exception);
        }
    }

    private static String runtimeVersion() {
        Package installerPackage = InstallerMain.class.getPackage();
        String implementationVersion = installerPackage == null ? null : installerPackage.getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank() ? "dev" : implementationVersion;
    }

    private static Path detectRepoRoot() {
        try {
            return InstallerSupport.findRepoRoot(Path.of("").toAbsolutePath());
        } catch (RuntimeException ignored) {
            return Path.of("").toAbsolutePath().normalize();
        }
    }

    private static Path detectDemoWar(Path repoRoot) {
        if (repoRoot == null) {
            return null;
        }
        Path libs = repoRoot.resolve("demo-business-app").resolve("build").resolve("libs");
        if (!Files.isDirectory(libs)) {
            return null;
        }
        try (Stream<Path> files = Files.list(libs)) {
            return files.filter(path -> path.getFileName().toString().startsWith("daisybase-demo-business"))
                    .filter(path -> path.getFileName().toString().endsWith(".war"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize())
                    .orElse(null);
        } catch (IOException exception) {
            return null;
        }
    }

    private static String demoJdbcUrl(InstallationPlan plan) {
        return "jdbc:daisybase:embedded:" + plan.databaseHome().resolve("demo-business").toAbsolutePath().normalize();
    }

    private static String pathText(Path path) {
        return path == null ? "" : path.toString();
    }

    private static Path normalize(Path value, Path defaultValue) {
        return (value == null ? defaultValue : value).toAbsolutePath().normalize();
    }

    private static Path normalizeRequired(String value, String label) {
        return Path.of(requireText(value, label)).toAbsolutePath().normalize();
    }

    private static Path normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value.trim()).toAbsolutePath().normalize();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return value.trim();
    }

    private static String normalizeContextPath(String contextPath) {
        String normalized = requireText(contextPath, "Context path").trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Context path must not be empty.");
        }
        if (normalized.contains(" ")) {
            throw new IllegalArgumentException("Context path must not contain spaces.");
        }
        return normalized;
    }

    private static String prompt(BufferedReader reader, Writer output, String label, String defaultValue, boolean allowBlank)
            throws IOException {
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

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static String encodePropertyPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String escapeProperty(String value) {
        return value.replace("\n", " ").replace("\r", " ");
    }

    private static String shellEscape(String value) {
        return value.replace("\"", "\\\"");
    }

    private static JPanel row(String label, JTextField field, Boolean directoryOnly, JDialog dialog) {
        JPanel row = new JPanel(new BorderLayout(8, 8));
        row.add(new JLabel(label), BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        if (directoryOnly != null) {
            JButton browse = new JButton("Browse");
            browse.addActionListener(event -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(directoryOnly ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
                if (!field.getText().isBlank()) {
                    chooser.setSelectedFile(Path.of(field.getText()).toFile());
                }
                int result = chooser.showOpenDialog(dialog);
                if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                    field.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            });
            row.add(browse, BorderLayout.EAST);
        }
        return row;
    }
}
