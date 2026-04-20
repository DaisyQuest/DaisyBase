package dev.daisybase.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaisyBaseUrlTest {
    @Test
    void parsesEmbeddedUrlsAndProperties() {
        Properties properties = new Properties();
        properties.setProperty("checkpointInterval", "17");
        properties.setProperty("strictDurability", "false");

        DaisyBaseUrl url = DaisyBaseUrl.parse("jdbc:daisybase:embedded:./data/home", properties);

        assertEquals(DaisyBaseUrl.Mode.EMBEDDED, url.mode());
        assertEquals(Path.of("./data/home").toAbsolutePath().normalize(), url.databaseHome());
        assertEquals(17, url.checkpointInterval());
        assertEquals(false, url.strictDurability());
    }

    @Test
    void parsesRemoteUrlsAndProperties() {
        Properties properties = new Properties();
        properties.setProperty("clientName", "jdbc-test");
        properties.setProperty("socketTimeoutMillis", "3210");

        DaisyBaseUrl url = DaisyBaseUrl.parse("jdbc:daisybase:remote://localhost:19999", properties);

        assertEquals(DaisyBaseUrl.Mode.REMOTE, url.mode());
        assertEquals("localhost", url.host());
        assertEquals(19999, url.port());
        assertEquals("jdbc-test", url.clientName());
        assertEquals(3210, url.socketTimeoutMillis());
    }

    @Test
    void acceptsOnlyDaisyBaseJdbcUrls() {
        assertTrue(DaisyBaseUrl.accepts("jdbc:daisybase:embedded:C:/db"));
        assertTrue(DaisyBaseUrl.accepts("jdbc:daisybase:remote://127.0.0.1"));
        assertTrue(DaisyBaseUrl.accepts("jdbc:javadb:embedded:C:/db"));
        assertTrue(DaisyBaseUrl.accepts("jdbc:javadb:remote://127.0.0.1"));
    }

    @Test
    void parsesLegacyJavaDbUrlsForCompatibility() {
        DaisyBaseUrl embedded = DaisyBaseUrl.parse("jdbc:javadb:embedded:./legacy/home", new Properties());
        DaisyBaseUrl remote = DaisyBaseUrl.parse("jdbc:javadb:remote://localhost:17001", new Properties());

        assertEquals(DaisyBaseUrl.Mode.EMBEDDED, embedded.mode());
        assertEquals(Path.of("./legacy/home").toAbsolutePath().normalize(), embedded.databaseHome());
        assertEquals(DaisyBaseUrl.Mode.REMOTE, remote.mode());
        assertEquals("localhost", remote.host());
        assertEquals(17001, remote.port());
    }
}
