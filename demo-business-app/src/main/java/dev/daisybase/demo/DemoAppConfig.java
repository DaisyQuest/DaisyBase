package dev.daisybase.demo;

import java.nio.file.Path;

public record DemoAppConfig(String jdbcUrl, String user, String password, String enterpriseName) {
    public static DemoAppConfig load() {
        return new DemoAppConfig(
                firstValue("daisybase.demo.jdbcUrl", "JAVADB_DEMO_JDBC_URL", defaultJdbcUrl()),
                firstValue("daisybase.demo.user", "JAVADB_DEMO_USER", ""),
                firstValue("daisybase.demo.password", "JAVADB_DEMO_PASSWORD", ""),
                firstValue("daisybase.demo.enterpriseName", "JAVADB_DEMO_ENTERPRISE_NAME", "Northwind Field Systems")
        );
    }

    private static String firstValue(String propertyName, String environmentName, String defaultValue) {
        String property = System.getProperty(propertyName);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String environment = System.getenv(environmentName);
        if (environment != null && !environment.isBlank()) {
            return environment.trim();
        }
        return defaultValue;
    }

    private static String defaultJdbcUrl() {
        return "jdbc:daisybase:embedded:" + Path.of(System.getProperty("user.home"), ".daisybase", "demo-business")
                .toAbsolutePath()
                .normalize();
    }
}
