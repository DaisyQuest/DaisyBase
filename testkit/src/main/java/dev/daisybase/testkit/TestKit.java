package dev.daisybase.testkit;

import dev.daisybase.engine.EmbeddedDatabaseEngine;
import dev.daisybase.engine.EngineApi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public final class TestKit {
    private static final String TEMP_HOME_PREFIX = "daisybase-test-";

    private TestKit() {
    }

    public static EngineApi.DatabaseEngine openTempEngine() {
        return tempEngine();
    }

    public static TempEngine tempEngine() {
        return tempEngine(8, true);
    }

    public static TempEngine tempEngine(int checkpointInterval, boolean strictDurability) {
        TempHome tempHome = tempHome();
        try {
            return tempHome.openManagedEngine(checkpointInterval, strictDurability);
        } catch (RuntimeException exception) {
            tempHome.close();
            throw exception;
        }
    }

    public static TempHome tempHome() {
        try {
            return new TempHome(Files.createTempDirectory(TEMP_HOME_PREFIX));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create temp engine", exception);
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete temp home " + root, exception);
        }
    }

    public static final class TempHome implements AutoCloseable {
        private final Path path;
        private boolean closed;

        private TempHome(Path path) {
            this.path = path;
        }

        public Path path() {
            return path;
        }

        public Path resolve(String first, String... more) {
            return path.resolve(Path.of(first, more));
        }

        public EngineApi.DatabaseEngine openEngine() {
            return openEngine(8, true);
        }

        public EngineApi.DatabaseEngine openEngine(int checkpointInterval, boolean strictDurability) {
            ensureOpen();
            return EmbeddedDatabaseEngine.open(new EngineApi.DatabaseConfig(path, checkpointInterval, strictDurability));
        }

        public TempEngine openManagedEngine() {
            return openManagedEngine(8, true);
        }

        public TempEngine openManagedEngine(int checkpointInterval, boolean strictDurability) {
            ensureOpen();
            return new TempEngine(this, checkpointInterval, strictDurability);
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Temp home is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            deleteRecursively(path);
            closed = true;
        }
    }

    public static final class TempEngine implements EngineApi.DatabaseEngine {
        private final TempHome tempHome;
        private final int checkpointInterval;
        private final boolean strictDurability;
        private EngineApi.DatabaseEngine engine;
        private boolean closed;

        private TempEngine(TempHome tempHome, int checkpointInterval, boolean strictDurability) {
            this.tempHome = tempHome;
            this.checkpointInterval = checkpointInterval;
            this.strictDurability = strictDurability;
            this.engine = tempHome.openEngine(checkpointInterval, strictDurability);
        }

        public Path home() {
            return tempHome.path();
        }

        public TempEngine restart() {
            ensureOpen();
            closeEngine();
            engine = tempHome.openEngine(checkpointInterval, strictDurability);
            return this;
        }

        @Override
        public EngineApi.Session openSession() {
            ensureOpen();
            return engine.openSession();
        }

        @Override
        public void checkpoint() {
            ensureOpen();
            engine.checkpoint();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            try {
                closeEngine();
            } finally {
                tempHome.close();
                closed = true;
            }
        }

        private void closeEngine() {
            if (engine != null) {
                engine.close();
                engine = null;
            }
        }

        private void ensureOpen() {
            if (closed || engine == null) {
                throw new IllegalStateException("Temp engine is closed");
            }
        }
    }
}
