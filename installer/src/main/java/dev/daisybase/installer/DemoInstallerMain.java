package dev.daisybase.installer;

import java.io.PrintWriter;
import java.io.Reader;

final class DemoInstallerMain {
    private DemoInstallerMain() {
    }

    static int run(String[] args, Reader input, PrintWriter output) throws Exception {
        DemoInstallerSupport.CommandLine commandLine;
        try {
            commandLine = DemoInstallerSupport.parseArgs(args);
        } catch (RuntimeException exception) {
            output.println("Argument error: " + exception.getMessage());
            printUsage(output);
            return 2;
        }
        if (commandLine.help()) {
            printUsage(output);
            return 0;
        }

        DemoInstallerSupport.InstallationPlan defaults = DemoInstallerSupport.defaultPlan(commandLine);
        DemoInstallerSupport.InstallationPlan plan;
        if (commandLine.gui()) {
            try {
                plan = DemoInstallerSupport.promptForPlanGui(defaults);
            } catch (IllegalStateException exception) {
                output.println("GUI error: " + exception.getMessage());
                return 2;
            }
            if (plan == null) {
                output.println("Installation cancelled.");
                return 1;
            }
        } else if (commandLine.nonInteractive() || commandLine.acceptDefaults()) {
            plan = defaults;
        } else {
            plan = DemoInstallerSupport.promptForPlan(defaults, input, output);
        }

        DemoInstallerSupport.performInstall(plan);
        output.println("Installed DaisyBase Demo Business stack into " + plan.installDir());
        output.println("TomEE home: " + plan.installDir().resolve("tomee"));
        output.println("Open: " + DemoInstallerSupport.applicationUrl(plan));
        return 0;
    }

    private static void printUsage(PrintWriter output) {
        output.println("Usage: installer --profile demo-business [options]");
        output.println("  --gui");
        output.println("  --repo-root <path>");
        output.println("  --install-dir <path>");
        output.println("  --database-home <path>");
        output.println("  --java-home <path>");
        output.println("  --tomee-home <path>");
        output.println("  --demo-war <path>");
        output.println("  --http-port <port>");
        output.println("  --context-path <path-segment>");
        output.println("  --enterprise-name <name>");
        output.println("  --non-interactive");
        output.println("  --accept-defaults");
    }
}
