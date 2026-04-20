package dev.daisybase.demo;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAssetTest {
    @Test
    void frontendAssetsContainExpectedHooks() throws Exception {
        Path moduleRoot = resolveModuleRoot();
        String html = Files.readString(moduleRoot.resolve("src/main/webapp/index.html"));
        String javascript = Files.readString(moduleRoot.resolve("src/main/webapp/assets/app.js"));
        String stylesheet = Files.readString(moduleRoot.resolve("src/main/webapp/assets/styles.css"));

        assertTrue(html.contains("id=\"metrics\""));
        assertTrue(html.contains("id=\"order-form\""));
        assertTrue(html.contains("assets/app.js"));
        assertTrue(html.contains("assets/styles.css"));

        assertTrue(javascript.contains("const apiBase = new URL(\"api/\", window.location.href);"));
        assertTrue(javascript.contains("function fulfillOrder(orderId)"));
        assertTrue(javascript.contains("directory/customers"));
        assertTrue(javascript.contains("directory/products"));

        assertTrue(stylesheet.contains("--accent"));
        assertTrue(stylesheet.contains(".metric-grid"));
        assertTrue(stylesheet.contains(".panel"));
        assertTrue(stylesheet.contains(".order-card"));
    }

    private Path resolveModuleRoot() {
        Path root = Path.of("").toAbsolutePath().normalize();
        if (root.getFileName() != null && "demo-business-app".equals(root.getFileName().toString())) {
            return root;
        }
        return root.resolve("demo-business-app");
    }
}
