package dev.daisybase.installer;

import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;

public final class InstallerMain {
    private InstallerMain() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = run(args, new InputStreamReader(System.in), new PrintWriter(System.out, true));
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, Reader input, PrintWriter output) throws Exception {
        if (isDemoBusinessProfile(args)) {
            return DemoInstallerMain.run(args, input, output);
        }
        InstallerSupport.CommandLine commandLine;
        try {
            commandLine = InstallerSupport.parseArgs(args);
        } catch (RuntimeException exception) {
            output.println("Argument error: " + exception.getMessage());
            printUsage(output);
            return 2;
        }
        if (commandLine.help()) {
            printUsage(output);
            return 0;
        }
        InstallerSupport.InstallationPlan defaults = InstallerSupport.defaultPlan(commandLine);
        InstallerSupport.InstallationPlan plan = (commandLine.nonInteractive() || commandLine.acceptDefaults())
                ? defaults
                : InstallerSupport.promptForPlan(defaults, input, output);
        InstallerSupport.performInstall(plan);
        output.println("Installed DaisyBase into " + plan.installDir());
        output.println("Config file: " + plan.installDir().resolve("config").resolve("daisybase.properties"));
        return 0;
    }

    private static void printUsage(PrintWriter output) {
        output.println("Usage: installer [options]");
        output.println("  --repo-root <path>");
        output.println("  --install-dir <path>");
        output.println("  --database-home <path>");
        output.println("  --java-home <path>");
        output.println("  --reference-parser-home <path>");
        output.println("  --reference-parser-mode <disabled|auto|required>");
        output.println("  --cli-dist <path>");
        output.println("  --server-dist <path>");
        output.println("  --port <port>");
        output.println("  --checkpoint-interval <n>");
        output.println("  --strict-durability <true|false>");
        output.println("  --non-interactive");
        output.println("  --accept-defaults");
        output.println();
        output.println("Demo business app:");
        output.println("  installer --profile demo-business [--gui|--non-interactive] ...");
    }

    private static boolean isDemoBusinessProfile(String[] args) {
        for (int index = 0; index < args.length - 1; index++) {
            if ("--profile".equals(args[index]) && "demo-business".equalsIgnoreCase(args[index + 1])) {
                return true;
            }
        }
        return false;
    }
}
