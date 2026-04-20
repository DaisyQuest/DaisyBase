package dev.daisybase.sql;

import dev.daisybase.common.Common;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlFrontendReferenceFixtureTest {
    private enum Outcome {
        PARSES,
        UNSUPPORTED,
        PARSE_ERROR
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("referenceFixtures")
    void coversReferenceGrammarFixture(String fixtureName, Outcome outcome, int statementCount) {
        String sql = readFixture(fixtureName);
        if (outcome == Outcome.PARSES) {
            SqlFrontend.StatementBatch batch = SqlFrontend.parseBatch(sql);
            assertEquals(statementCount, batch.statements().size());
            return;
        }

        Common.DatabaseException error = assertThrows(Common.DatabaseException.class, () -> SqlFrontend.parseBatch(sql));
        Common.ErrorCode expected = outcome == Outcome.UNSUPPORTED
                ? Common.ErrorCode.UNSUPPORTED_FEATURE
                : Common.ErrorCode.PARSE_ERROR;
        assertEquals(expected, error.code());
    }

    private static Stream<Arguments> referenceFixtures() {
        return Stream.of(
                fixture("ddl_alter_table.sql", Outcome.UNSUPPORTED),
                fixture("ddl_comment_on.sql", Outcome.UNSUPPORTED),
                fixture("ddl_create_index.sql", Outcome.PARSES, 1),
                fixture("ddl_create_materialized_view.sql", Outcome.UNSUPPORTED),
                fixture("ddl_create_sequence.sql", Outcome.PARSES, 1),
                fixture("ddl_create_synonym.sql", Outcome.UNSUPPORTED),
                fixture("ddl_create_table.sql", Outcome.UNSUPPORTED),
                fixture("ddl_create_view.sql", Outcome.UNSUPPORTED),
                fixture("ddl_drop_table.sql", Outcome.UNSUPPORTED),
                fixture("ddl_grant_revoke.sql", Outcome.UNSUPPORTED),
                fixture("ddl_integration.sql", Outcome.UNSUPPORTED),
                fixture("ddl_truncate.sql", Outcome.UNSUPPORTED),
                fixture("dml_delete.sql", Outcome.UNSUPPORTED),
                fixture("dml_insert.sql", Outcome.UNSUPPORTED),
                fixture("dml_integration.sql", Outcome.UNSUPPORTED),
                fixture("dml_lock_table.sql", Outcome.UNSUPPORTED),
                fixture("dml_merge.sql", Outcome.UNSUPPORTED),
                fixture("dml_returning.sql", Outcome.UNSUPPORTED),
                fixture("dml_update.sql", Outcome.UNSUPPORTED),
                fixture("exp_case_expression.sql", Outcome.UNSUPPORTED),
                fixture("exp_cast_convert_type_constructor.sql", Outcome.UNSUPPORTED),
                fixture("exp_collection_table_expression.sql", Outcome.PARSES, 1),
                fixture("exp_expression_precedence.sql", Outcome.PARSES, 2),
                fixture("exp_function_calls.sql", Outcome.PARSES, 1),
                fixture("exp_json_xml_expression.sql", Outcome.PARSES, 1),
                fixture("exp_model_expression.sql", Outcome.PARSES, 1),
                fixture("pls_001_anonymous_block.sql", Outcome.UNSUPPORTED),
                fixture("pls_002_procedure.sql", Outcome.PARSES, 1),
                fixture("pls_003_function.sql", Outcome.PARSES, 1),
                fixture("pls_004_package.sql", Outcome.UNSUPPORTED),
                fixture("pls_005_trigger.sql", Outcome.UNSUPPORTED),
                fixture("pls_006_type_declarations.sql", Outcome.UNSUPPORTED),
                fixture("pls_007_variable_declarations.sql", Outcome.UNSUPPORTED),
                fixture("pls_008_cursor_declarations.sql", Outcome.UNSUPPORTED),
                fixture("pls_009_exceptions.sql", Outcome.UNSUPPORTED),
                fixture("pls_010_pragma_declarations.sql", Outcome.UNSUPPORTED),
                fixture("pls_030_bulk_collect_forall.sql", Outcome.UNSUPPORTED),
                fixture("pls_031_autonomous_transaction.sql", Outcome.UNSUPPORTED),
                fixture("pls_032_pipelined_functions.sql", Outcome.UNSUPPORTED),
                fixture("pls_033_result_cache.sql", Outcome.UNSUPPORTED),
                fixture("pls_034_deterministic.sql", Outcome.UNSUPPORTED),
                fixture("pls_035_inline_plsql.sql", Outcome.UNSUPPORTED),
                fixture("pls_036_invoker_rights.sql", Outcome.UNSUPPORTED),
                fixture("pls_advanced_integration.sql", Outcome.UNSUPPORTED),
                fixture("pls_case_searched.sql", Outcome.UNSUPPORTED),
                fixture("pls_case_simple.sql", Outcome.UNSUPPORTED),
                fixture("pls_control_integration.sql", Outcome.UNSUPPORTED),
                fixture("pls_cursor_statements.sql", Outcome.UNSUPPORTED),
                fixture("pls_cursor_torture.sql", Outcome.PARSE_ERROR),
                fixture("pls_execute_immediate.sql", Outcome.UNSUPPORTED),
                fixture("pls_exit_continue.sql", Outcome.UNSUPPORTED),
                fixture("pls_goto_label.sql", Outcome.PARSE_ERROR),
                fixture("pls_if_elsif_else.sql", Outcome.UNSUPPORTED),
                fixture("pls_integration.sql", Outcome.UNSUPPORTED),
                fixture("pls_loop_basic.sql", Outcome.UNSUPPORTED),
                fixture("pls_loop_for.sql", Outcome.UNSUPPORTED),
                fixture("pls_loop_while.sql", Outcome.UNSUPPORTED),
                fixture("pls_null.sql", Outcome.UNSUPPORTED),
                fixture("pls_return.sql", Outcome.UNSUPPORTED),
                fixture("select_analytic.sql", Outcome.UNSUPPORTED),
                fixture("select_basic.sql", Outcome.PARSES, 1),
                fixture("select_cte.sql", Outcome.UNSUPPORTED),
                fixture("select_cte_pivot_window_frame.sql", Outcome.PARSE_ERROR),
                fixture("select_group_by.sql", Outcome.PARSES, 1),
                fixture("select_having.sql", Outcome.PARSES, 1),
                fixture("select_hints.sql", Outcome.PARSES, 2),
                fixture("select_integration.sql", Outcome.PARSE_ERROR),
                fixture("select_join.sql", Outcome.UNSUPPORTED),
                fixture("select_order_by.sql", Outcome.UNSUPPORTED),
                fixture("select_set_operators.sql", Outcome.UNSUPPORTED),
                fixture("select_subqueries.sql", Outcome.UNSUPPORTED),
                fixture("select_where.sql", Outcome.PARSES, 1)
        );
    }

    private static Arguments fixture(String name, Outcome outcome) {
        return fixture(name, outcome, 0);
    }

    private static Arguments fixture(String name, Outcome outcome, int statementCount) {
        return Arguments.of(name, outcome, statementCount);
    }

    private static String readFixture(String fixtureName) {
        try {
            return Files.readString(referenceFixtureRoot().resolve(fixtureName));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load fixture " + fixtureName, exception);
        }
    }

    private static Path referenceFixtureRoot() {
        for (Path cursor = Path.of("").toAbsolutePath(); cursor != null; cursor = cursor.getParent()) {
            Path sibling = cursor.resolveSibling("PLSQL-Parser").resolve("tests").resolve("grammar").resolve("fixtures");
            if (Files.isDirectory(sibling)) {
                return sibling;
            }
            Path child = cursor.resolve("PLSQL-Parser").resolve("tests").resolve("grammar").resolve("fixtures");
            if (Files.isDirectory(child)) {
                return child;
            }
        }
        throw new IllegalStateException("Expected sibling PLSQL-Parser tests/grammar/fixtures directory");
    }
}
