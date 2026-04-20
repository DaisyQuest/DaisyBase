package dev.daisybase.bench;

import dev.daisybase.common.Common;
import dev.daisybase.engine.EmbeddedDatabaseEngine;
import dev.daisybase.engine.EngineApi;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class EmbeddedEngineBenchmark {
    private static final String[] DEPARTMENTS = {"ENG", "SALES", "SUPPORT", "OPS", "HR", "FINANCE"};
    private static final String GROUPED_COUNT_SQL = """
            SELECT department, COUNT(*) AS total, COUNT(salary) AS paid
            FROM payroll
            GROUP BY department
            HAVING COUNT(*) >= 150
            ORDER BY department;
            """;
    private static final String WRITE_READ_SQL = """
            BEGIN;
            INSERT INTO events VALUES (1, 'payload');
            SELECT payload FROM events WHERE id = 1;
            ROLLBACK;
            """;

    @Benchmark
    public Common.TupleBatch groupedCountQuery(GroupedQueryState state) {
        return state.session.execute(GROUPED_COUNT_SQL).statements().getFirst().batch();
    }

    @Benchmark
    public EngineApi.BatchResult transactionalWriteReadFlow(WriteReadState state) {
        return state.session.execute(WRITE_READ_SQL);
    }

    @State(Scope.Thread)
    public static class GroupedQueryState extends EngineState {
        @Setup(Level.Trial)
        public void setUp() {
            openEngine();
            session.execute("CREATE TABLE payroll (department TEXT NOT NULL, salary INT);");
            session.execute(buildPayrollInsertSql(1_200));
        }
    }

    @State(Scope.Thread)
    public static class WriteReadState extends EngineState {
        @Setup(Level.Trial)
        public void setUp() {
            openEngine();
            session.execute("CREATE TABLE events (id INT PRIMARY KEY, payload TEXT NOT NULL);");
        }
    }

    private abstract static class EngineState {
        private Path tempHome;
        protected EngineApi.DatabaseEngine engine;
        protected EngineApi.Session session;

        protected void openEngine() {
            try {
                tempHome = Files.createTempDirectory("daisybase-bench-");
                engine = EmbeddedDatabaseEngine.open(tempHome);
                session = engine.openSession();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to open benchmark engine", exception);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            try {
                if (session != null) {
                    session.close();
                }
            } finally {
                try {
                    if (engine != null) {
                        engine.close();
                    }
                } finally {
                    deleteRecursively(tempHome);
                }
            }
        }
    }

    private static String buildPayrollInsertSql(int rowCount) {
        StringBuilder sql = new StringBuilder("INSERT INTO payroll VALUES ");
        for (int index = 0; index < rowCount; index++) {
            if (index > 0) {
                sql.append(", ");
            }
            String department = DEPARTMENTS[index % DEPARTMENTS.length];
            String salary = index % 5 == 0 ? "NULL" : Integer.toString(45 + (index % 60));
            sql.append("('").append(department).append("', ").append(salary).append(")");
        }
        return sql.append(';').toString();
    }

    private static void deleteRecursively(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete benchmark home " + root, exception);
        }
    }
}
