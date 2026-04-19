package dev.javadb.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDbUrlTest {
    @Test
    void parsesEmbeddedUrlsAndProperties() {
        Properties properties = new Properties();
        properties.setProperty("checkpointInterval", "17");
        properties.setProperty("strictDurability", "false");

        JavaDbUrl url = JavaDbUrl.parse("jdbc:javadb:embedded:./data/home", properties);

        assertEquals(JavaDbUrl.Mode.EMBEDDED, url.mode());
        assertEquals(Path.of("./data/home").toAbsolutePath().normalize(), url.databaseHome());
        assertEquals(17, url.checkpointInterval());
        assertEquals(false, url.strictDurability());
    }

    @Test
    void parsesRemoteUrlsAndProperties() {
        Properties properties = new Properties();
        properties.setProperty("clientName", "jdbc-test");
        properties.setProperty("socketTimeoutMillis", "3210");

        JavaDbUrl url = JavaDbUrl.parse("jdbc:javadb:remote://localhost:19999", properties);

        assertEquals(JavaDbUrl.Mode.REMOTE, url.mode());
        assertEquals("localhost", url.host());
        assertEquals(19999, url.port());
        assertEquals("jdbc-test", url.clientName());
        assertEquals(3210, url.socketTimeoutMillis());
    }

    @Test
    void acceptsOnlyJavaDbJdbcUrls() {
        assertTrue(JavaDbUrl.accepts("jdbc:javadb:embedded:C:/db"));
        assertTrue(JavaDbUrl.accepts("jdbc:javadb:remote://127.0.0.1"));
    }
}
