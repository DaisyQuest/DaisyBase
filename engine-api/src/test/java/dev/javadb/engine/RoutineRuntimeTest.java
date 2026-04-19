package dev.javadb.engine;

import dev.javadb.catalog.Catalog;
import dev.javadb.common.Common;
import dev.javadb.execution.Execution;
import dev.javadb.sql.SqlFrontend;
import dev.javadb.txn.Transactions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutineRuntimeTest {
    @Test
    void executesProceduresWithExpressionArgumentsAndSubstitutesSqlStatements() {
        Catalog.CatalogSnapshot snapshot = snapshot(
                tableChange(2, "audit_log",
                        column(0, "id", Common.DataType.BIGINT, false, true, true),
                        column(1, "label", Common.DataType.TEXT, true, false, false),
                        column(2, "amount", Common.DataType.DECIMAL, true, false, false)),
                new Catalog.CreateSequenceChange(new Common.ObjectId(3),
                        new Catalog.QualifiedName("public", "order_seq"),
                        new Catalog.SequenceOptions(7L, 1L, null, null, 20, false)),
                functionChange(4, "bump_total", Common.DataType.DECIMAL,
                        "BEGIN RETURN ABS(p_amount) + 1.25; END",
                        parameter(0, "p_amount", Common.DataType.DECIMAL, Catalog.ParameterMode.IN)),
                procedureChange(5, "touch_order",
                        "BEGIN NULL; END",
                        parameter(0, "p_id", Common.DataType.BIGINT, Catalog.ParameterMode.IN),
                        parameter(1, "p_label", Common.DataType.TEXT, Catalog.ParameterMode.IN),
                        parameter(2, "p_total", Common.DataType.DECIMAL, Catalog.ParameterMode.IN)),
                procedureChange(6, "finalize_order",
                        """
                        OR REPLACE BEGIN
                          NULL;
                          p_label := UPPER(TRIM(REPLACE(SUBSTR(COALESCE(p_label, ' ready box '), 2, 5), ' ', '_')));
                          p_total := public.bump_total(p_total) + NEXT VALUE FOR public.order_seq;
                          INSERT INTO audit_log VALUES (p_id, p_label, p_total);
                          UPDATE audit_log SET amount = p_total WHERE id = p_id;
                          DELETE FROM audit_log WHERE id = p_id;
                          EXPLAIN SELECT id FROM audit_log WHERE amount >= p_total GROUP BY id HAVING COUNT(*) >= 1 ORDER BY p_total;
                          CALL public.touch_order(p_id, p_label, p_total);
                        END
                        """,
                        parameter(0, "p_id", Common.DataType.BIGINT, Catalog.ParameterMode.IN),
                        parameter(1, "p_label", Common.DataType.TEXT, Catalog.ParameterMode.OUT),
                        parameter(2, "p_total", Common.DataType.DECIMAL, Catalog.ParameterMode.INOUT))
        );
        RoutineHarness harness = harness(snapshot, 7L);
        Catalog.RoutineDefinition finalizeOrder = snapshot.routine(new Catalog.QualifiedName("public", "finalize_order")).orElseThrow();

        RoutineRuntime.CallOutcome outcome = harness.runtime().executeCall(finalizeOrder, List.of(
                SqlFrontend.parseExpressionFragment("41"),
                SqlFrontend.parseExpressionFragment("ignored_out_value"),
                SqlFrontend.parseExpressionFragment("2.25")
        ), transaction(snapshot), Common.SourceSpan.NONE);

        assertEquals("CALL", outcome.commandTag());
        assertEquals(2, outcome.batch().columns().size());
        assertEquals("READY", outcome.batch().rows().getFirst().get(0).asText());
        assertDecimalEquals("10.50", outcome.batch().rows().getFirst().get(1).asDecimal());

        assertEquals(5, harness.executedStatements().size());

        SqlFrontend.InsertStatement insert = assertInstanceOf(SqlFrontend.InsertStatement.class, harness.executedStatements().get(0));
        assertEquals(41L, literal(insert.rows().getFirst().get(0)).asLong());
        assertEquals("READY", literal(insert.rows().getFirst().get(1)).asText());
        assertDecimalEquals("10.50", literal(insert.rows().getFirst().get(2)).asDecimal());

        SqlFrontend.UpdateStatement update = assertInstanceOf(SqlFrontend.UpdateStatement.class, harness.executedStatements().get(1));
        assertDecimalEquals("10.50", literal(update.assignments().getFirst().expression()).asDecimal());
        SqlFrontend.BinaryExpression updateWhere = assertInstanceOf(SqlFrontend.BinaryExpression.class, update.where());
        assertEquals(41L, literal(updateWhere.right()).asLong());

        SqlFrontend.DeleteStatement delete = assertInstanceOf(SqlFrontend.DeleteStatement.class, harness.executedStatements().get(2));
        SqlFrontend.BinaryExpression deleteWhere = assertInstanceOf(SqlFrontend.BinaryExpression.class, delete.where());
        assertEquals(41L, literal(deleteWhere.right()).asLong());

        SqlFrontend.ExplainStatement explain = assertInstanceOf(SqlFrontend.ExplainStatement.class, harness.executedStatements().get(3));
        SqlFrontend.SelectStatement explained = assertInstanceOf(SqlFrontend.SelectStatement.class, explain.statement());
        SqlFrontend.BinaryExpression selectWhere = assertInstanceOf(SqlFrontend.BinaryExpression.class, explained.where());
        assertDecimalEquals("10.50", literal(selectWhere.right()).asDecimal());
        assertDecimalEquals("10.50", literal(explained.orderBy().getFirst().expression()).asDecimal());

        SqlFrontend.CallStatement nestedCall = assertInstanceOf(SqlFrontend.CallStatement.class, harness.executedStatements().get(4));
        assertEquals(41L, literal(nestedCall.arguments().get(0)).asLong());
        assertEquals("READY", literal(nestedCall.arguments().get(1)).asText());
        assertDecimalEquals("10.50", literal(nestedCall.arguments().get(2)).asDecimal());
    }

    @Test
    void invokesNumericBooleanAndStringFunctionsAcrossEdgeCases() {
        Catalog.CatalogSnapshot snapshot = snapshot(
                new Catalog.CreateSequenceChange(new Common.ObjectId(10),
                        new Catalog.QualifiedName("public", "calc_seq"),
                        new Catalog.SequenceOptions(4L, 1L, null, null, 20, false)),
                functionChange(11, "math_pack", Common.DataType.DECIMAL,
                        "BEGIN RETURN ((ABS(p_value) + 1.50) * 2 - 1.00) / 2; END",
                        parameter(0, "p_value", Common.DataType.DECIMAL, Catalog.ParameterMode.IN)),
                functionChange(12, "abs_int", Common.DataType.INTEGER,
                        "BEGIN RETURN ABS(p_value); END",
                        parameter(0, "p_value", Common.DataType.INTEGER, Catalog.ParameterMode.IN)),
                functionChange(13, "abs_big", Common.DataType.BIGINT,
                        "BEGIN RETURN ABS(p_value); END",
                        parameter(0, "p_value", Common.DataType.BIGINT, Catalog.ParameterMode.IN)),
                functionChange(14, "bool_and", Common.DataType.BOOLEAN,
                        "BEGIN RETURN p_left AND p_right; END",
                        parameter(0, "p_left", Common.DataType.BOOLEAN, Catalog.ParameterMode.IN),
                        parameter(1, "p_right", Common.DataType.BOOLEAN, Catalog.ParameterMode.IN)),
                functionChange(15, "bool_or", Common.DataType.BOOLEAN,
                        "BEGIN RETURN p_left OR p_right; END",
                        parameter(0, "p_left", Common.DataType.BOOLEAN, Catalog.ParameterMode.IN),
                        parameter(1, "p_right", Common.DataType.BOOLEAN, Catalog.ParameterMode.IN)),
                functionChange(16, "shape_label", Common.DataType.TEXT,
                        "BEGIN RETURN REPLACE(SUBSTR(TRIM(COALESCE(p_text, '  ready box  ')), p_start, p_length), ' ', '_'); END",
                        parameter(0, "p_text", Common.DataType.TEXT, Catalog.ParameterMode.IN),
                        parameter(1, "p_start", Common.DataType.BIGINT, Catalog.ParameterMode.IN),
                        parameter(2, "p_length", Common.DataType.BIGINT, Catalog.ParameterMode.IN)),
                functionChange(160, "shape_without_length", Common.DataType.TEXT,
                        "BEGIN RETURN SUBSTR(TRIM(COALESCE(p_text, '  ready box  ')), p_start); END",
                        parameter(0, "p_text", Common.DataType.TEXT, Catalog.ParameterMode.IN),
                        parameter(1, "p_start", Common.DataType.BIGINT, Catalog.ParameterMode.IN)),
                functionChange(161, "replace_nullable", Common.DataType.TEXT,
                        "BEGIN RETURN REPLACE(p_text, p_search, p_replacement); END",
                        parameter(0, "p_text", Common.DataType.TEXT, Catalog.ParameterMode.IN),
                        parameter(1, "p_search", Common.DataType.TEXT, Catalog.ParameterMode.IN),
                        parameter(2, "p_replacement", Common.DataType.TEXT, Catalog.ParameterMode.IN)),
                functionChange(162, "upper_nullable", Common.DataType.TEXT,
                        "BEGIN RETURN UPPER(p_text); END",
                        parameter(0, "p_text", Common.DataType.TEXT, Catalog.ParameterMode.IN)),
                functionChange(17, "text_len", Common.DataType.BIGINT,
                        "BEGIN RETURN LENGTH(LOWER(p_text)); END",
                        parameter(0, "p_text", Common.DataType.TEXT, Catalog.ParameterMode.IN)),
                functionChange(18, "nullable_trim", Common.DataType.TEXT,
                        "BEGIN RETURN TRIM(p_text); END",
                        parameter(0, "p_text", Common.DataType.TEXT, Catalog.ParameterMode.IN)),
                functionChange(19, "next_calc", Common.DataType.BIGINT,
                        "BEGIN RETURN NEXT VALUE FOR public.calc_seq; END")
        );
        RoutineHarness harness = harness(snapshot, 4L);

        assertDecimalEquals("4.50", harness.invoke("math_pack", Common.Value.decimal(new BigDecimal("-3.50"))).asDecimal());
        assertEquals(4, harness.invoke("abs_int", Common.Value.integer(-4)).asInt());
        assertEquals(9L, harness.invoke("abs_big", Common.Value.bigint(-9L)).asLong());

        assertEquals(false, harness.invoke("bool_and",
                Common.Value.bool(false), Common.Value.nullValue(Common.DataType.BOOLEAN)).asBoolean());
        assertTrue(harness.invoke("bool_and",
                Common.Value.bool(true), Common.Value.bool(true)).asBoolean());
        assertTrue(harness.invoke("bool_and",
                Common.Value.bool(true), Common.Value.nullValue(Common.DataType.BOOLEAN)).isNull());
        assertTrue(harness.invoke("bool_or",
                Common.Value.bool(true), Common.Value.nullValue(Common.DataType.BOOLEAN)).asBoolean());
        assertTrue(harness.invoke("bool_or",
                Common.Value.bool(false), Common.Value.bool(false)).type() == Common.DataType.BOOLEAN);
        assertEquals(false, harness.invoke("bool_or",
                Common.Value.bool(false), Common.Value.bool(false)).asBoolean());
        Common.Value nullOr = harness.invoke("bool_or",
                Common.Value.nullValue(Common.DataType.BOOLEAN), Common.Value.bool(false));
        assertTrue(nullOr.isNull());

        assertEquals("ready", harness.invoke("shape_label",
                Common.Value.nullValue(Common.DataType.TEXT), Common.Value.bigint(1L), Common.Value.bigint(5L)).asText());
        assertEquals("A_B", harness.invoke("shape_label",
                Common.Value.text(" A B "), Common.Value.bigint(1L), Common.Value.bigint(5L)).asText());
        assertEquals("ai", harness.invoke("shape_label",
                Common.Value.text("trail"), Common.Value.bigint(-3L), Common.Value.bigint(2L)).asText());
        assertEquals("tra", harness.invoke("shape_label",
                Common.Value.text("trail"), Common.Value.bigint(0L), Common.Value.bigint(3L)).asText());
        assertEquals("", harness.invoke("shape_label",
                Common.Value.text("trail"), Common.Value.bigint(1L), Common.Value.bigint(0L)).asText());
        assertTrue(harness.invoke("shape_label",
                Common.Value.text("trail"), Common.Value.bigint(1L), Common.Value.nullValue(Common.DataType.BIGINT)).isNull());
        assertEquals("ady box", harness.invoke("shape_without_length",
                Common.Value.nullValue(Common.DataType.TEXT), Common.Value.bigint(3L)).asText());
        assertTrue(harness.invoke("replace_nullable",
                Common.Value.text("abc"), Common.Value.nullValue(Common.DataType.TEXT), Common.Value.text("x")).isNull());
        assertTrue(harness.invoke("upper_nullable", Common.Value.nullValue(Common.DataType.TEXT)).isNull());

        assertEquals(5L, harness.invoke("text_len", Common.Value.text("HeLLo")).asLong());
        assertTrue(harness.invoke("text_len", Common.Value.nullValue(Common.DataType.TEXT)).isNull());
        assertTrue(harness.invoke("nullable_trim", Common.Value.nullValue(Common.DataType.TEXT)).isNull());
        assertEquals("trimmed", harness.invoke("nullable_trim", Common.Value.text("  trimmed  ")).asText());

        assertEquals(4L, harness.invoke("next_calc").asLong());
        assertEquals(5L, harness.invoke("next_calc").asLong());
    }

    @Test
    void reportsRoutineValidationAndResolutionErrorsExplicitly() {
        Catalog.CatalogSnapshot snapshot = snapshot(
                procedureChange(20, "touch_order",
                        "BEGIN NULL; END",
                        parameter(0, "p_id", Common.DataType.BIGINT, Catalog.ParameterMode.IN)),
                functionChange(21, "missing_return", Common.DataType.BIGINT,
                        "BEGIN NULL; END",
                        parameter(0, "p_id", Common.DataType.BIGINT, Catalog.ParameterMode.IN)),
                procedureChange(22, "bad_return",
                        "BEGIN RETURN 1; END",
                        parameter(0, "p_id", Common.DataType.BIGINT, Catalog.ParameterMode.IN)),
                procedureChange(23, "bad_variable",
                        "BEGIN p_missing := 1; END",
                        parameter(0, "p_id", Common.DataType.BIGINT, Catalog.ParameterMode.IN)),
                functionChange(24, "missing_sequence", Common.DataType.BIGINT,
                        "BEGIN RETURN NEXT VALUE FOR public.unknown_seq; END"),
                functionChange(25, "calls_procedure", Common.DataType.BIGINT,
                        "BEGIN RETURN public.touch_order(1); END"),
                functionChange(26, "calls_unknown", Common.DataType.BIGINT,
                        "BEGIN RETURN public.unknown_func(1); END"),
                functionChange(27, "bad_math", Common.DataType.DECIMAL,
                        "BEGIN RETURN 'x' + 1; END")
        );
        RoutineHarness harness = harness(snapshot, 1L);

        Common.DatabaseException missingReturn = assertThrows(Common.DatabaseException.class,
                () -> harness.invoke("missing_return", Common.Value.bigint(1L)));
        assertEquals(Common.ErrorCode.SEMANTIC_ERROR, missingReturn.code());

        Catalog.RoutineDefinition badReturn = snapshot.routine(new Catalog.QualifiedName("public", "bad_return")).orElseThrow();
        Common.DatabaseException procedureReturn = assertThrows(Common.DatabaseException.class,
                () -> harness.runtime().executeCall(badReturn, List.of(Common.Value.bigint(1L)), transaction(snapshot)));
        assertEquals(Common.ErrorCode.SEMANTIC_ERROR, procedureReturn.code());

        Catalog.RoutineDefinition badVariable = snapshot.routine(new Catalog.QualifiedName("public", "bad_variable")).orElseThrow();
        Common.DatabaseException unknownVariable = assertThrows(Common.DatabaseException.class,
                () -> harness.runtime().executeCall(badVariable, List.of(Common.Value.bigint(1L)), transaction(snapshot)));
        assertEquals(Common.ErrorCode.SEMANTIC_ERROR, unknownVariable.code());

        Common.DatabaseException missingSequence = assertThrows(Common.DatabaseException.class,
                () -> harness.invoke("missing_sequence"));
        assertEquals(Common.ErrorCode.SEMANTIC_ERROR, missingSequence.code());

        Common.DatabaseException callsProcedure = assertThrows(Common.DatabaseException.class,
                () -> harness.invoke("calls_procedure"));
        assertEquals(Common.ErrorCode.SEMANTIC_ERROR, callsProcedure.code());

        Common.DatabaseException unknownFunction = assertThrows(Common.DatabaseException.class,
                () -> harness.invoke("calls_unknown"));
        assertEquals(Common.ErrorCode.UNSUPPORTED_FEATURE, unknownFunction.code());

        Common.DatabaseException badMath = assertThrows(Common.DatabaseException.class,
                () -> harness.invoke("bad_math"));
        assertEquals(Common.ErrorCode.SEMANTIC_ERROR, badMath.code());
    }

    @Test
    void rejectsWrongArgumentCountsForCallsAndFunctions() {
        Catalog.CatalogSnapshot snapshot = snapshot(
                functionChange(30, "single_arg", Common.DataType.BIGINT,
                        "BEGIN RETURN p_id; END",
                        parameter(0, "p_id", Common.DataType.BIGINT, Catalog.ParameterMode.IN)),
                procedureChange(31, "single_call",
                        "BEGIN NULL; END",
                        parameter(0, "p_id", Common.DataType.BIGINT, Catalog.ParameterMode.IN))
        );
        RoutineHarness harness = harness(snapshot, 1L);
        Catalog.RoutineDefinition singleCall = snapshot.routine(new Catalog.QualifiedName("public", "single_call")).orElseThrow();

        Common.DatabaseException functionArity = assertThrows(Common.DatabaseException.class,
                () -> harness.invoke("single_arg", Common.Value.bigint(1L), Common.Value.bigint(2L)));
        assertEquals(Common.ErrorCode.SEMANTIC_ERROR, functionArity.code());

        Common.DatabaseException callArity = assertThrows(Common.DatabaseException.class,
                () -> harness.runtime().executeCall(singleCall, List.of(
                        SqlFrontend.parseExpressionFragment("1"),
                        SqlFrontend.parseExpressionFragment("2")
                ), transaction(snapshot), Common.SourceSpan.NONE));
        assertEquals(Common.ErrorCode.SEMANTIC_ERROR, callArity.code());
    }

    @Test
    void executeCallReturnsReturnValueBatchesForFunctions() {
        Catalog.CatalogSnapshot snapshot = snapshot(
                functionChange(40, "callable_total", Common.DataType.DECIMAL,
                        "BEGIN RETURN ABS(p_value) + 0.50; END",
                        parameter(0, "p_value", Common.DataType.DECIMAL, Catalog.ParameterMode.IN))
        );
        RoutineHarness harness = harness(snapshot, 1L);
        Catalog.RoutineDefinition callableTotal = snapshot.routine(new Catalog.QualifiedName("public", "callable_total")).orElseThrow();

        RoutineRuntime.CallOutcome outcome = harness.runtime().executeCall(callableTotal, List.of(
                SqlFrontend.parseExpressionFragment("-2.25")
        ), transaction(snapshot), Common.SourceSpan.NONE);

        assertEquals("CALL", outcome.commandTag());
        assertEquals("RETURN_VALUE", outcome.batch().columns().getFirst().name());
        assertDecimalEquals("2.75", outcome.batch().rows().getFirst().get(0).asDecimal());
    }

    private static Catalog.CatalogSnapshot snapshot(Catalog.CatalogChange... changes) {
        return Catalog.applyChanges(Catalog.bootstrap(new Common.ObjectId(1)), List.of(changes));
    }

    private static Catalog.CreateTableChange tableChange(long id, String name, Catalog.ColumnDefinition... columns) {
        return new Catalog.CreateTableChange(new Common.ObjectId(id), new Catalog.QualifiedName("public", name), List.of(columns));
    }

    private static Catalog.ColumnDefinition column(int ordinal, String name, Common.DataType type,
                                                   boolean nullable, boolean primaryKey, boolean unique) {
        return new Catalog.ColumnDefinition(ordinal, name, type, nullable, primaryKey, unique, null, null);
    }

    private static Catalog.CreateRoutineChange functionChange(long id, String name, Common.DataType returnType,
                                                              String bodySql, Catalog.RoutineParameter... parameters) {
        return new Catalog.CreateRoutineChange(new Common.ObjectId(id), new Catalog.QualifiedName("public", name),
                Catalog.RoutineKind.FUNCTION, List.of(parameters), returnType, bodySql);
    }

    private static Catalog.CreateRoutineChange procedureChange(long id, String name, String bodySql,
                                                               Catalog.RoutineParameter... parameters) {
        return new Catalog.CreateRoutineChange(new Common.ObjectId(id), new Catalog.QualifiedName("public", name),
                Catalog.RoutineKind.PROCEDURE, List.of(parameters), null, bodySql);
    }

    private static Catalog.RoutineParameter parameter(int ordinal, String name, Common.DataType type, Catalog.ParameterMode mode) {
        return new Catalog.RoutineParameter(ordinal, name, type, mode);
    }

    private static Transactions.TransactionState transaction(Catalog.CatalogSnapshot snapshot) {
        return new Transactions.TransactionManager(0).begin(Common.IsolationLevel.READ_COMMITTED, snapshot);
    }

    private static Common.Value literal(SqlFrontend.Expression expression) {
        return assertInstanceOf(SqlFrontend.LiteralExpression.class, expression).value();
    }

    private static void assertDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private static RoutineHarness harness(Catalog.CatalogSnapshot snapshot, long sequenceStart) {
        List<SqlFrontend.Statement> executedStatements = new ArrayList<>();
        AtomicLong nextSequence = new AtomicLong(sequenceStart);
        Execution.SequenceAllocator allocator = new Execution.SequenceAllocator() {
            @Override
            public Common.Value nextValue(Catalog.SequenceDefinition sequence) {
                return Common.Value.bigint(nextSequence.getAndIncrement());
            }

            @Override
            public Common.Value nextIdentityValue(Catalog.TableDefinition table, Catalog.ColumnDefinition column) {
                throw new UnsupportedOperationException("Identity allocation is not used in routine tests");
            }

            @Override
            public void observeIdentityValue(Catalog.TableDefinition table, Catalog.ColumnDefinition column, Common.Value value) {
                throw new UnsupportedOperationException("Identity observation is not used in routine tests");
            }
        };
        AtomicReference<RoutineRuntime> runtimeRef = new AtomicReference<>();
        RoutineRuntime runtime = new RoutineRuntime(
                snapshot,
                allocator,
                (statement, transactionState) -> {
                    executedStatements.add(statement);
                    return new EngineApi.StatementResult("SQL", 0L, Common.TupleBatch.empty(), Common.TupleBatch.empty(), null);
                },
                (routine, arguments, transactionState) -> runtimeRef.get().invokeFunction(routine, arguments, transactionState));
        runtimeRef.set(runtime);
        return new RoutineHarness(runtime, executedStatements, snapshot);
    }

    private record RoutineHarness(RoutineRuntime runtime, List<SqlFrontend.Statement> executedStatements,
                                  Catalog.CatalogSnapshot snapshot) {
        Common.Value invoke(String functionName, Common.Value... arguments) {
            Catalog.RoutineDefinition routine = snapshot.routine(new Catalog.QualifiedName("public", functionName)).orElseThrow();
            return runtime.invokeFunction(routine, List.of(arguments), transaction(snapshot));
        }
    }
}
