package dev.javadb.sql;

import dev.javadb.common.Common;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlFrontendTest {
    @Test
    void parsesBatchWithCreateInsertAndSelect() {
        SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch("""
                CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL, age INT CHECK (age >= 0));
                INSERT INTO users (id, name, age) VALUES (1, 'Ada', 36), (2, 'Grace', 40);
                SELECT COUNT(*) AS total FROM users WHERE age >= 36 ORDER BY name;
                """);

        assertEquals(3, batch.statements().size());
        assertInstanceOf(SqlFrontend.CreateTableStatement.class, batch.statements().get(0));
        assertInstanceOf(SqlFrontend.InsertStatement.class, batch.statements().get(1));
        SqlFrontend.SelectStatement select = assertInstanceOf(SqlFrontend.SelectStatement.class, batch.statements().get(2));
        assertTrue(select.selectItems().getFirst().expression() instanceof SqlFrontend.FunctionCallExpression);
        assertNotNull(select.where());
        assertEquals(1, select.orderBy().size());
    }

    @Test
    void parsesStandaloneExpressionFragment() {
        SqlFrontend.Expression expression = SqlFrontend.parseExpressionFragment("age >= 0 AND active = TRUE");
        SqlFrontend.BinaryExpression andExpression = assertInstanceOf(SqlFrontend.BinaryExpression.class, expression);
        assertEquals(SqlFrontend.BinaryOperator.AND, andExpression.operator());
        Common.Value literal = ((SqlFrontend.LiteralExpression) ((SqlFrontend.BinaryExpression) andExpression.right()).right()).value();
        assertEquals(Common.DataType.BOOLEAN, literal.type());
        assertTrue(literal.asBoolean());
    }

    @Test
    void parsesOracleStyleCreateTableNatively() {
        SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch(
                "CREATE TABLE users (id NUMBER CONSTRAINT users_pk PRIMARY KEY, name VARCHAR2(30) NOT NULL, age NUMBER CHECK (age >= 0));");

        SqlFrontend.CreateTableStatement createTable = assertInstanceOf(SqlFrontend.CreateTableStatement.class, batch.statements().getFirst());
        assertEquals("number", createTable.columns().get(0).typeName().toLowerCase());
        assertTrue(createTable.columns().get(0).primaryKey());
        assertTrue(createTable.columns().get(1).notNull());
        assertEquals("varchar2", createTable.columns().get(1).typeName().toLowerCase());
        assertNotNull(createTable.columns().get(2).checkExpression());
    }

    @Test
    void appliesSingleColumnTableConstraintsToColumns() {
        SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch("""
                CREATE TABLE users (
                    id NUMBER,
                    name VARCHAR2(30),
                    age NUMBER,
                    CONSTRAINT users_pk PRIMARY KEY (id),
                    CONSTRAINT users_name_uq UNIQUE (name),
                    CONSTRAINT users_age_ck CHECK (age >= 0)
                );
                """);

        SqlFrontend.CreateTableStatement createTable = assertInstanceOf(SqlFrontend.CreateTableStatement.class, batch.statements().getFirst());
        assertTrue(createTable.columns().get(0).primaryKey());
        assertTrue(createTable.columns().get(0).unique());
        assertTrue(createTable.columns().get(0).notNull());
        assertTrue(createTable.columns().get(1).unique());
        assertNotNull(createTable.columns().get(2).checkExpression());
    }

    @Test
    void rejectsCompositeTableConstraintsExplicitly() {
        Common.DatabaseException error = assertThrows(Common.DatabaseException.class, () -> SqlFrontend.parseBatch("""
                CREATE TABLE users (
                    id NUMBER,
                    tenant_id NUMBER,
                    CONSTRAINT users_pk PRIMARY KEY (tenant_id, id)
                );
                """));

        assertEquals(Common.ErrorCode.UNSUPPORTED_FEATURE, error.code());
    }

    @Test
    void parsesFetchFirstClauseAndImplicitSelectAlias() {
        SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch(
                "SELECT COUNT(*) total FROM users ORDER BY id FETCH FIRST 5 ROWS ONLY;");

        SqlFrontend.SelectStatement select = assertInstanceOf(SqlFrontend.SelectStatement.class, batch.statements().getFirst());
        assertEquals("total", select.selectItems().getFirst().alias());
        assertEquals(5, select.limit());
        assertEquals(1, select.orderBy().size());
    }

    @Test
    void parsesExpressionPrecedenceReferenceCases() {
        SqlFrontend.SelectStatement arithmetic = assertInstanceOf(SqlFrontend.SelectStatement.class,
                SqlFrontend.parseBatch("SELECT 1 + 2 * 3 - 4 / 2 FROM dual;").statements().getFirst());

        SqlFrontend.BinaryExpression subtraction = assertInstanceOf(SqlFrontend.BinaryExpression.class,
                arithmetic.selectItems().getFirst().expression());
        assertEquals(SqlFrontend.BinaryOperator.SUB, subtraction.operator());
        SqlFrontend.BinaryExpression addition = assertInstanceOf(SqlFrontend.BinaryExpression.class, subtraction.left());
        assertEquals(SqlFrontend.BinaryOperator.ADD, addition.operator());
        assertEquals(SqlFrontend.BinaryOperator.MUL,
                assertInstanceOf(SqlFrontend.BinaryExpression.class, addition.right()).operator());
        assertEquals(SqlFrontend.BinaryOperator.DIV,
                assertInstanceOf(SqlFrontend.BinaryExpression.class, subtraction.right()).operator());

        SqlFrontend.SelectStatement logical = assertInstanceOf(SqlFrontend.SelectStatement.class,
                SqlFrontend.parseBatch("SELECT a = 1 OR b = 2 AND c = 3 FROM t;").statements().getFirst());
        SqlFrontend.BinaryExpression orExpression = assertInstanceOf(SqlFrontend.BinaryExpression.class,
                logical.selectItems().getFirst().expression());
        assertEquals(SqlFrontend.BinaryOperator.OR, orExpression.operator());
        assertEquals(SqlFrontend.BinaryOperator.AND,
                assertInstanceOf(SqlFrontend.BinaryExpression.class, orExpression.right()).operator());
    }

    @Test
    void parsesFunctionCallsReferenceCase() {
        SqlFrontend.SelectStatement select = assertInstanceOf(SqlFrontend.SelectStatement.class,
                SqlFrontend.parseBatch("SELECT SUM(a), schema.func(b, 2), COUNT(*) FROM t;").statements().getFirst());

        SqlFrontend.FunctionCallExpression sum = assertInstanceOf(SqlFrontend.FunctionCallExpression.class,
                select.selectItems().get(0).expression());
        assertEquals("SUM", sum.name());
        assertEquals(1, sum.arguments().size());

        SqlFrontend.FunctionCallExpression qualified = assertInstanceOf(SqlFrontend.FunctionCallExpression.class,
                select.selectItems().get(1).expression());
        assertEquals("SCHEMA.FUNC", qualified.name());
        assertEquals(2, qualified.arguments().size());

        SqlFrontend.FunctionCallExpression count = assertInstanceOf(SqlFrontend.FunctionCallExpression.class,
                select.selectItems().get(2).expression());
        assertEquals("COUNT", count.name());
        assertInstanceOf(SqlFrontend.StarExpression.class, count.arguments().getFirst());
    }

    @Test
    void parsesGroupByAndHavingClauses() {
        SqlFrontend.SelectStatement select = assertInstanceOf(SqlFrontend.SelectStatement.class,
                SqlFrontend.parseBatch("""
                        SELECT department, COUNT(*) AS total, COUNT(salary) AS paid
                        FROM employees
                        WHERE active = TRUE
                        GROUP BY department
                        HAVING COUNT(*) >= 2
                        ORDER BY department;
                        """).statements().getFirst());

        assertEquals(3, select.selectItems().size());
        assertEquals(1, select.groupBy().size());
        assertNotNull(select.having());
        assertEquals(1, select.orderBy().size());
    }

    @Test
    void parsesSequenceIdentityRoutineAndCallContractsNatively() {
        SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch("""
                CREATE SEQUENCE public.order_seq START WITH 100 INCREMENT BY 5 NO MINVALUE MAXVALUE 1000 CACHE 32 CYCLE;
                CREATE TABLE orders (
                    id BIGINT GENERATED ALWAYS AS IDENTITY (START WITH 1000 INCREMENT BY 10 NO CYCLE),
                    external_id BIGINT GENERATED BY DEFAULT AS IDENTITY,
                    customer_id BIGINT NOT NULL
                );
                CREATE FUNCTION public.add_one(p_value BIGINT) RETURN BIGINT IS BEGIN RETURN p_value + 1; END;
                CREATE PROCEDURE public.mark_ready(p_id BIGINT, p_msg OUT TEXT) AS BEGIN NULL; END;
                CALL public.mark_ready(1, 'hello');
                SELECT NEXT VALUE FOR public.order_seq FROM orders;
                """);

        SqlFrontend.CreateSequenceStatement sequence = assertInstanceOf(SqlFrontend.CreateSequenceStatement.class, batch.statements().get(0));
        assertEquals("order_seq", sequence.sequenceName().name());
        assertEquals(100L, sequence.options().startWith());
        assertEquals(5L, sequence.options().incrementBy());
        assertEquals(1000L, sequence.options().maxValue());
        assertEquals(32, sequence.options().cacheSize());
        assertTrue(sequence.options().cycle());

        SqlFrontend.CreateTableStatement createTable = assertInstanceOf(SqlFrontend.CreateTableStatement.class, batch.statements().get(1));
        assertEquals(SqlFrontend.IdentityGeneration.ALWAYS, createTable.columns().get(0).identityDefinition().generation());
        assertEquals(SqlFrontend.IdentityGeneration.BY_DEFAULT, createTable.columns().get(1).identityDefinition().generation());
        assertTrue(createTable.columns().get(0).notNull());

        SqlFrontend.CreateRoutineStatement function = assertInstanceOf(SqlFrontend.CreateRoutineStatement.class, batch.statements().get(2));
        assertEquals(SqlFrontend.RoutineKind.FUNCTION, function.kind());
        assertEquals("bigint", function.returnTypeName());
        assertTrue(function.bodySql().contains("RETURN p_value + 1"));

        SqlFrontend.CreateRoutineStatement procedure = assertInstanceOf(SqlFrontend.CreateRoutineStatement.class, batch.statements().get(3));
        assertEquals(SqlFrontend.RoutineKind.PROCEDURE, procedure.kind());
        assertEquals(2, procedure.parameters().size());
        assertEquals(SqlFrontend.ParameterMode.OUT, procedure.parameters().get(1).mode());

        SqlFrontend.CallStatement call = assertInstanceOf(SqlFrontend.CallStatement.class, batch.statements().get(4));
        assertEquals(2, call.arguments().size());

        SqlFrontend.SelectStatement select = assertInstanceOf(SqlFrontend.SelectStatement.class, batch.statements().get(5));
        assertInstanceOf(SqlFrontend.NextValueExpression.class, select.selectItems().getFirst().expression());
    }

    @Test
    void parsesTypedLiteralsAndInoutRoutineParameters() {
        SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch("""
                CREATE PROCEDURE public.finish_job(p_id INT, p_total INOUT DECIMAL) AS BEGIN NULL; END;
                INSERT INTO ledger VALUES (1, 12.50, DATE '2026-04-19', TIME '10:15:30', TIMESTAMP '2026-04-19T10:15:30');
                """);

        SqlFrontend.CreateRoutineStatement procedure = assertInstanceOf(SqlFrontend.CreateRoutineStatement.class, batch.statements().getFirst());
        assertEquals(SqlFrontend.ParameterMode.INOUT, procedure.parameters().get(1).mode());

        SqlFrontend.InsertStatement insert = assertInstanceOf(SqlFrontend.InsertStatement.class, batch.statements().get(1));
        assertEquals(new BigDecimal("12.50"),
                assertInstanceOf(SqlFrontend.LiteralExpression.class, insert.rows().getFirst().get(1)).value().asDecimal());
        assertEquals(LocalDate.parse("2026-04-19"),
                assertInstanceOf(SqlFrontend.LiteralExpression.class, insert.rows().getFirst().get(2)).value().asDate());
        assertEquals(LocalTime.parse("10:15:30"),
                assertInstanceOf(SqlFrontend.LiteralExpression.class, insert.rows().getFirst().get(3)).value().asTime());
        assertEquals(LocalDateTime.parse("2026-04-19T10:15:30"),
                assertInstanceOf(SqlFrontend.LiteralExpression.class, insert.rows().getFirst().get(4)).value().asTimestamp());
    }

    @Test
    void preservesDeclaredDecimalPrecisionAndScaleAcrossTablesAndRoutines() {
        SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch("""
                CREATE TABLE ledger (
                    amount DECIMAL(12, 2) NOT NULL,
                    tax NUMERIC(9),
                    ratio DECIMAL
                );
                CREATE FUNCTION public.add_fee(p_amount DECIMAL(10, 3), p_tax NUMERIC(7)) RETURN DECIMAL(14, 4) IS
                BEGIN
                  RETURN p_amount + p_tax;
                END;
                """);

        SqlFrontend.CreateTableStatement table = assertInstanceOf(SqlFrontend.CreateTableStatement.class, batch.statements().getFirst());
        assertEquals(12, table.columns().get(0).typePrecision());
        assertEquals(2, table.columns().get(0).typeScale());
        assertEquals(9, table.columns().get(1).typePrecision());
        assertEquals(null, table.columns().get(1).typeScale());
        assertEquals(null, table.columns().get(2).typePrecision());
        assertEquals(null, table.columns().get(2).typeScale());

        SqlFrontend.CreateRoutineStatement function = assertInstanceOf(SqlFrontend.CreateRoutineStatement.class, batch.statements().get(1));
        assertEquals(10, function.parameters().get(0).typePrecision());
        assertEquals(3, function.parameters().get(0).typeScale());
        assertEquals(7, function.parameters().get(1).typePrecision());
        assertEquals(null, function.parameters().get(1).typeScale());
        assertEquals(14, function.returnTypePrecision());
        assertEquals(4, function.returnTypeScale());
    }

    @Test
    void fallsBackToUnqualifiedDecimalMetadataWhenNumericTypeArgumentsAreMalformed() {
        SqlFrontend.CreateTableStatement table = assertInstanceOf(SqlFrontend.CreateTableStatement.class,
                SqlFrontend.parseBatch("CREATE TABLE weird_types (amount DECIMAL(foo, bar), tax NUMERIC(cost));")
                        .statements()
                        .getFirst());

        assertEquals(null, table.columns().get(0).typePrecision());
        assertEquals(null, table.columns().get(0).typeScale());
        assertEquals(null, table.columns().get(1).typePrecision());
        assertEquals(null, table.columns().get(1).typeScale());
    }

    @Test
    void nativeRoutineDdlWinsOverReferenceBridgePreference() {
        Assumptions.assumeTrue(ReferencePlsqlParserBridge.isUsableForTests());
        String previousMode = System.getProperty("javadb.sql.referenceParser.mode");
        try {
            System.setProperty("javadb.sql.referenceParser.mode", "auto");
            SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch("""
                    CREATE FUNCTION public.bump(p_value BIGINT) RETURN BIGINT IS
                    BEGIN
                      RETURN p_value + 1;
                    END;
                    """);

            SqlFrontend.CreateRoutineStatement routine = assertInstanceOf(SqlFrontend.CreateRoutineStatement.class, batch.statements().getFirst());
            assertEquals(SqlFrontend.RoutineKind.FUNCTION, routine.kind());
        } finally {
            restoreProperty("javadb.sql.referenceParser.mode", previousMode);
        }
    }

    @Test
    void rejectsUnsupportedReferenceGrammarExplicitly() {
        Common.DatabaseException createView = assertThrows(Common.DatabaseException.class,
                () -> SqlFrontend.parseBatch("CREATE VIEW emp_view AS SELECT id FROM employees;"));
        assertEquals(Common.ErrorCode.UNSUPPORTED_FEATURE, createView.code());

        Common.DatabaseException merge = assertThrows(Common.DatabaseException.class,
                () -> SqlFrontend.parseBatch("MERGE INTO target t USING source s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.val = s.val;"));
        assertEquals(Common.ErrorCode.UNSUPPORTED_FEATURE, merge.code());
    }

    @Test
    void referenceBridgeParsesAnonymousBlocksWhenEnabled() {
        Assumptions.assumeTrue(ReferencePlsqlParserBridge.isUsableForTests());
        String previousMode = System.getProperty("javadb.sql.referenceParser.mode");
        try {
            System.setProperty("javadb.sql.referenceParser.mode", "auto");
            SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch("""
                    DECLARE
                      v_count NUMBER := 1;
                    BEGIN
                      NULL;
                    END;
                    """);

            SqlFrontend.ReferenceStatement statement = assertInstanceOf(SqlFrontend.ReferenceStatement.class, batch.statements().getFirst());
            assertEquals("PLSQL-REFERENCE", statement.dialect());
            assertEquals("ANONYMOUS_BLOCK", statement.externalType());
        } finally {
            restoreProperty("javadb.sql.referenceParser.mode", previousMode);
        }
    }

    @Test
    void referenceBridgePrefersAnonymousDmlBlocksOverTransactionBegin() {
        Assumptions.assumeTrue(ReferencePlsqlParserBridge.isUsableForTests());
        String previousMode = System.getProperty("javadb.sql.referenceParser.mode");
        try {
            System.setProperty("javadb.sql.referenceParser.mode", "auto");
            SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch("""
                    BEGIN
                      INSERT INTO users VALUES (1, 'Ada');
                      UPDATE users SET name = 'Ada Lovelace' WHERE id = 1;
                    END;
                    """);

            assertEquals(2, batch.statements().size());
            assertInstanceOf(SqlFrontend.InsertStatement.class, batch.statements().get(0));
            assertInstanceOf(SqlFrontend.UpdateStatement.class, batch.statements().get(1));
        } finally {
            restoreProperty("javadb.sql.referenceParser.mode", previousMode);
        }
    }

    @Test
    void nativeSqlRemainsOnNativeParserWhenReferenceBridgeIsEnabled() {
        Assumptions.assumeTrue(ReferencePlsqlParserBridge.isUsableForTests());
        String previousMode = System.getProperty("javadb.sql.referenceParser.mode");
        try {
            System.setProperty("javadb.sql.referenceParser.mode", "auto");
            SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch("SELECT id FROM users;");
            assertInstanceOf(SqlFrontend.SelectStatement.class, batch.statements().getFirst());
        } finally {
            restoreProperty("javadb.sql.referenceParser.mode", previousMode);
        }
    }

    @Test
    void requiredReferenceBridgeFailsClearlyWhenParserHomeIsMissing() {
        String previousMode = System.getProperty("javadb.sql.referenceParser.mode");
        String previousHome = System.getProperty("javadb.sql.referenceParser.home");
        try {
            System.setProperty("javadb.sql.referenceParser.mode", "required");
            System.setProperty("javadb.sql.referenceParser.home", tempMissingPath());
            Common.DatabaseException error = assertThrows(Common.DatabaseException.class,
                    () -> SqlFrontend.parseBatch("DECLARE BEGIN NULL; END;"));
            assertEquals(Common.ErrorCode.UNSUPPORTED_FEATURE, error.code());
        } finally {
            restoreProperty("javadb.sql.referenceParser.mode", previousMode);
            restoreProperty("javadb.sql.referenceParser.home", previousHome);
        }
    }

    @Test
    void parsesMutationAndTransactionStatements() {
        SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch("""
                CREATE SCHEMA analytics;
                BEGIN ISOLATION LEVEL READ COMMITTED;
                UPDATE users SET age = age + 1 WHERE id = 1;
                DELETE FROM users WHERE id = 2;
                EXPLAIN SELECT id FROM users WHERE id = 1;
                COMMIT;
                ROLLBACK;
                """);

        assertEquals(7, batch.statements().size());
        assertInstanceOf(SqlFrontend.CreateSchemaStatement.class, batch.statements().get(0));
        SqlFrontend.BeginStatement begin = assertInstanceOf(SqlFrontend.BeginStatement.class, batch.statements().get(1));
        assertEquals(Common.IsolationLevel.READ_COMMITTED, begin.isolationLevel());
        assertInstanceOf(SqlFrontend.UpdateStatement.class, batch.statements().get(2));
        assertInstanceOf(SqlFrontend.DeleteStatement.class, batch.statements().get(3));
        assertInstanceOf(SqlFrontend.ExplainStatement.class, batch.statements().get(4));
        assertInstanceOf(SqlFrontend.CommitStatement.class, batch.statements().get(5));
        assertInstanceOf(SqlFrontend.RollbackStatement.class, batch.statements().get(6));
    }

    @Test
    void rendersQualifiedNamesAndLiterals() {
        SqlFrontend.IdentifierExpression identifier = new SqlFrontend.IdentifierExpression(
                new SqlFrontend.QualifiedName("hr", "employees"),
                Common.SourceSpan.NONE);
        assertEquals("hr.employees", SqlFrontend.renderExpression(identifier));
        assertEquals("'Ada''s'", SqlFrontend.renderExpression(
                new SqlFrontend.LiteralExpression(Common.Value.text("Ada's"), Common.SourceSpan.NONE)));
        assertEquals("NULL", SqlFrontend.renderExpression(
                new SqlFrontend.LiteralExpression(Common.Value.text(null), Common.SourceSpan.NONE)));
    }

    @Test
    void parsesUnaryAndLiteralEdges() {
        SqlFrontend.Expression expression = SqlFrontend.parseExpressionFragment("-5 + +3");
        SqlFrontend.BinaryExpression addition = assertInstanceOf(SqlFrontend.BinaryExpression.class, expression);
        assertEquals(SqlFrontend.BinaryOperator.ADD, addition.operator());
        assertEquals(SqlFrontend.BinaryOperator.SUB,
                assertInstanceOf(SqlFrontend.BinaryExpression.class, addition.left()).operator());

        SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch(
                "INSERT INTO users VALUES (1, 'Ada''s', NULL, TRUE, FALSE);");
        SqlFrontend.InsertStatement insert = assertInstanceOf(SqlFrontend.InsertStatement.class, batch.statements().getFirst());
        assertEquals(1, insert.rows().size());
        assertEquals("Ada's", assertInstanceOf(SqlFrontend.LiteralExpression.class, insert.rows().getFirst().get(1)).value().asText());
        assertTrue(assertInstanceOf(SqlFrontend.LiteralExpression.class, insert.rows().getFirst().get(3)).value().asBoolean());
        assertTrue(!assertInstanceOf(SqlFrontend.LiteralExpression.class, insert.rows().getFirst().get(4)).value().asBoolean());
    }

    @Test
    void rejectsMalformedStrings() {
        Common.DatabaseException error = assertThrows(Common.DatabaseException.class,
                () -> SqlFrontend.parseBatch("INSERT INTO users VALUES ('unterminated);"));
        assertEquals(Common.ErrorCode.PARSE_ERROR, error.code());
    }

    @Test
    void skipsCommentsAndHints() {
        SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch("""
                -- session setup
                SELECT /*+ INDEX(t idx_users) */ id FROM users;
                /* trailing block comment */
                """);

        SqlFrontend.SelectStatement select = assertInstanceOf(SqlFrontend.SelectStatement.class, batch.statements().getFirst());
        assertEquals(1, batch.statements().size());
        assertEquals("users", select.from().name());
    }

    @Test
    void rejectsUnterminatedBlockComments() {
        Common.DatabaseException error = assertThrows(Common.DatabaseException.class,
                () -> SqlFrontend.parseBatch("SELECT 1 /* broken"));
        assertEquals(Common.ErrorCode.PARSE_ERROR, error.code());
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static String tempMissingPath() {
        return Path.of(System.getProperty("java.io.tmpdir"), "missing-plsql-parser-home").toString();
    }
}
