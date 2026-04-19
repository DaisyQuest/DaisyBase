package dev.javadb.engine;

import dev.javadb.common.Common;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsRelativePathsAndDefaults() throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Path configPath = configDir.resolve("javadb.properties");
        Files.writeString(configPath, """
                database.home=../data/home
                checkpoint.interval=12
                strict.durability=false
                server.port=16432
                reference.parser.mode=auto
                reference.parser.home=../../PLSQL-Parser
                java.home=../../jdk
                auth.user=app
                auth.password=secret
                """);

        LaunchConfig config = LaunchConfig.load(configPath);

        assertEquals(configDir.resolve("../data/home").normalize(), config.databaseConfig().home());
        assertEquals(12, config.databaseConfig().checkpointInterval());
        assertTrue(!config.databaseConfig().strictDurability());
        assertEquals(16432, config.serverPort());
        assertEquals("auto", config.referenceParserMode());
        assertEquals(configDir.resolve("../../PLSQL-Parser").normalize(), config.referenceParserHome());
        assertEquals(configDir.resolve("../../jdk").normalize(), config.javaHome());
        assertEquals("app", config.authUser());
        assertEquals("secret", config.authPassword());
    }

    @Test
    void defaultsLeaveOptionalPathsUnset() {
        LaunchConfig defaults = LaunchConfig.defaults(tempDir.resolve("db"));

        assertEquals(tempDir.resolve("db"), defaults.databaseConfig().home());
        assertEquals(8, defaults.databaseConfig().checkpointInterval());
        assertTrue(defaults.databaseConfig().strictDurability());
        assertEquals(LaunchConfig.DEFAULT_SERVER_PORT, defaults.serverPort());
        assertEquals("disabled", defaults.referenceParserMode());
        assertNull(defaults.referenceParserHome());
        assertNull(defaults.javaHome());
        assertEquals("", defaults.authUser());
        assertEquals("", defaults.authPassword());
    }

    @Test
    void normalizesBlankModesDefaultPortsAndAbsolutePaths() throws Exception {
        Path configDir = tempDir.resolve("cfg");
        Files.createDirectories(configDir);
        String absoluteDbHome = tempDir.resolve("abs-db").toAbsolutePath().toString().replace('\\', '/');
        String absoluteParserHome = tempDir.resolve("abs-parser").toAbsolutePath().toString().replace('\\', '/');
        Path configPath = configDir.resolve("javadb.properties");
        Files.writeString(configPath, """
                database.home=%s
                checkpoint.interval=
                strict.durability=true
                server.port=0
                reference.parser.mode=
                reference.parser.home=%s
                """.formatted(absoluteDbHome, absoluteParserHome));

        LaunchConfig config = LaunchConfig.load(configPath);

        assertEquals(Path.of(absoluteDbHome).normalize(), config.databaseConfig().home());
        assertEquals(8, config.databaseConfig().checkpointInterval());
        assertTrue(config.databaseConfig().strictDurability());
        assertEquals(LaunchConfig.DEFAULT_SERVER_PORT, config.serverPort());
        assertEquals("disabled", config.referenceParserMode());
        assertEquals(Path.of(absoluteParserHome).normalize(), config.referenceParserHome());
        assertNull(config.javaHome());

        LaunchConfig explicit = new LaunchConfig(EngineApi.DatabaseConfig.defaults(tempDir.resolve("direct")),
                -5, "   ", null, null, null, null);
        assertEquals(LaunchConfig.DEFAULT_SERVER_PORT, explicit.serverPort());
        assertEquals("disabled", explicit.referenceParserMode());
        assertEquals("", explicit.authUser());
        assertEquals("", explicit.authPassword());
    }

    @Test
    void rejectsInvalidIntegerFields() throws Exception {
        Path configPath = tempDir.resolve("invalid.properties");
        Files.writeString(configPath, """
                database.home=./db-home
                checkpoint.interval=not-a-number
                """);

        Common.DatabaseException exception = assertThrows(Common.DatabaseException.class, () -> LaunchConfig.load(configPath));
        assertEquals(Common.ErrorCode.SEMANTIC_ERROR, exception.code());
    }
}
