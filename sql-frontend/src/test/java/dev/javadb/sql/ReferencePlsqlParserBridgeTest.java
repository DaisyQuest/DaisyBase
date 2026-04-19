package dev.javadb.sql;

import dev.javadb.common.Common;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferencePlsqlParserBridgeTest {
    @Test
    void bridgeTranslatesSimpleInsertUpdateDeleteAndIndexStatements() {
        withReferenceBridge(() -> {
            SqlFrontend.StatementBatch insertBatch = ReferencePlsqlParserBridge.tryParse(
                    "INSERT INTO users (id, name) VALUES (1, 'Ada');",
                    new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE, "bridge"));
            SqlFrontend.InsertStatement insert = assertInstanceOf(SqlFrontend.InsertStatement.class, insertBatch.statements().getFirst());
            assertEquals("users", insert.tableName().name());
            assertEquals(2, insert.columns().size());

            SqlFrontend.StatementBatch updateBatch = ReferencePlsqlParserBridge.tryParse(
                    "UPDATE users SET name = UPPER(name) WHERE id = 1;",
                    new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE, "bridge"));
            SqlFrontend.UpdateStatement update = assertInstanceOf(SqlFrontend.UpdateStatement.class, updateBatch.statements().getFirst());
            assertEquals("users", update.tableName().name());
            SqlFrontend.FunctionCallExpression function = assertInstanceOf(SqlFrontend.FunctionCallExpression.class,
                    update.assignments().getFirst().expression());
            assertEquals("UPPER", function.name());

            SqlFrontend.StatementBatch deleteBatch = ReferencePlsqlParserBridge.tryParse(
                    "DELETE FROM users WHERE id = 1;",
                    new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE, "bridge"));
            assertInstanceOf(SqlFrontend.DeleteStatement.class, deleteBatch.statements().getFirst());

            SqlFrontend.StatementBatch indexBatch = ReferencePlsqlParserBridge.tryParse(
                    "CREATE INDEX users_name_idx ON users (name);",
                    new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE, "bridge"));
            SqlFrontend.CreateIndexStatement index = assertInstanceOf(SqlFrontend.CreateIndexStatement.class, indexBatch.statements().getFirst());
            assertEquals("users_name_idx", index.indexName());
            assertEquals(1, index.columns().size());
        });
    }

    @Test
    void bridgeFlattensAnonymousBlocksIntoExecutableStatements() {
        withReferenceBridge(() -> {
            SqlFrontend.StatementBatch batch = ReferencePlsqlParserBridge.tryPreferredParse("""
                    BEGIN
                      INSERT INTO users VALUES (1, 'Ada');
                      UPDATE users SET name = 'Ada Lovelace' WHERE id = 1;
                      DELETE FROM users WHERE id = 99;
                    END;
                    """);

            assertNotNull(batch);
            assertEquals(3, batch.statements().size());
            assertInstanceOf(SqlFrontend.InsertStatement.class, batch.statements().get(0));
            assertInstanceOf(SqlFrontend.UpdateStatement.class, batch.statements().get(1));
            assertInstanceOf(SqlFrontend.DeleteStatement.class, batch.statements().get(2));
        });
    }

    @Test
    void bridgeWrapsJoinQueriesAsReferenceStatements() {
        withReferenceBridge(() -> {
            SqlFrontend.StatementBatch batch = ReferencePlsqlParserBridge.tryParse("""
                    SELECT u.id, d.name
                    FROM users u JOIN departments d ON u.dept_id = d.id
                    ORDER BY u.id;
                    """,
                    new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE, "bridge"));

            SqlFrontend.ReferenceStatement statement = assertInstanceOf(SqlFrontend.ReferenceStatement.class, batch.statements().getFirst());
            assertEquals("QUERY_EXPRESSION", statement.externalType());
            assertEquals("QUERY_EXPRESSION", statement.ast().get("type"));
        });
    }

    @Test
    void bridgeSupportsExplainFallbackForReferenceQueries() {
        withReferenceBridge(() -> {
            SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch("""
                    EXPLAIN
                    SELECT u.id, d.name
                    FROM users u JOIN departments d ON u.dept_id = d.id
                    ORDER BY u.id;
                    """);

            SqlFrontend.ExplainStatement explain = assertInstanceOf(SqlFrontend.ExplainStatement.class, batch.statements().getFirst());
            assertInstanceOf(SqlFrontend.ReferenceStatement.class, explain.statement());
        });
    }

    @Test
    void bridgeTranslatesCheckConstraintsFromReferenceAst() {
        withReferenceBridge(() -> {
            SqlFrontend.StatementBatch batch = ReferencePlsqlParserBridge.tryParse("""
                    CREATE TABLE users (
                        id NUMBER,
                        age NUMBER,
                        CONSTRAINT users_age_ck CHECK (age >= 0)
                    );
                    """,
                    new Common.DatabaseException(Common.ErrorCode.UNSUPPORTED_FEATURE, "bridge"));

            SqlFrontend.CreateTableStatement createTable = assertInstanceOf(SqlFrontend.CreateTableStatement.class, batch.statements().getFirst());
            assertTrue(createTable.columns().stream().anyMatch(column -> column.checkExpression() != null));
        });
    }

    private void withReferenceBridge(Runnable runnable) {
        Assumptions.assumeTrue(ReferencePlsqlParserBridge.isUsableForTests());
        String previousMode = System.getProperty("javadb.sql.referenceParser.mode");
        try {
            System.setProperty("javadb.sql.referenceParser.mode", "auto");
            runnable.run();
        } finally {
            restoreProperty("javadb.sql.referenceParser.mode", previousMode);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
