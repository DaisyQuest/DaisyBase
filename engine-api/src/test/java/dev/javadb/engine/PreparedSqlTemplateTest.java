package dev.javadb.engine;

import dev.javadb.common.Common;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedSqlTemplateTest {
    @Test
    void parsesAndRendersParameterizedStatementsWithoutTouchingQuotedQuestionMarks() {
        PreparedSqlTemplate template = PreparedSqlTemplate.parse(
                "SELECT ?, 'literal ? value', 'it''s still text ?' FROM dual WHERE note = ?;   ");

        assertEquals(2, template.parameterCount());
        assertEquals("SELECT 1, 'literal ? value', 'it''s still text ?' FROM dual WHERE note = 'done'   ",
                template.render(List.of("1", "'done'")));
        assertEquals("SELECT NULL, 'literal ? value', 'it''s still text ?' FROM dual WHERE note = NULL   ",
                template.renderNulls());
    }

    @Test
    void rejectsEmptyMultipleOrUnterminatedPreparedStatements() {
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> PreparedSqlTemplate.parse("   "));
        assertEquals("Prepared SQL must not be empty", empty.getMessage());

        IllegalArgumentException multiple = assertThrows(IllegalArgumentException.class,
                () -> PreparedSqlTemplate.parse("SELECT ?; SELECT 2"));
        assertEquals("Prepared statements require exactly one SQL statement", multiple.getMessage());

        IllegalArgumentException terminatorThenPlaceholder = assertThrows(IllegalArgumentException.class,
                () -> PreparedSqlTemplate.parse("SELECT 1; ?"));
        assertEquals("Prepared statements require exactly one SQL statement", terminatorThenPlaceholder.getMessage());

        IllegalArgumentException unterminated = assertThrows(IllegalArgumentException.class,
                () -> PreparedSqlTemplate.parse("SELECT 'broken"));
        assertEquals("Unterminated string literal in prepared SQL", unterminated.getMessage());
    }

    @Test
    void renderRejectsWrongArityAndUnboundParameters() {
        PreparedSqlTemplate template = PreparedSqlTemplate.parse("SELECT ? + ?");

        IllegalArgumentException wrongArity = assertThrows(IllegalArgumentException.class,
                () -> template.render(List.of("1")));
        assertEquals("Expected 2 parameters but received 1", wrongArity.getMessage());

        java.util.ArrayList<String> parameters = new java.util.ArrayList<>();
        parameters.add("1");
        parameters.add(null);
        IllegalArgumentException unbound = assertThrows(IllegalArgumentException.class,
                () -> template.render(parameters));
        assertEquals("Parameter 2 is not bound", unbound.getMessage());
    }

    @Test
    void preparedStatementDescriptionValidatesConstructorArgumentsAndNormalizesColumns() {
        EngineApi.PreparedStatementDescription noColumns = new EngineApi.PreparedStatementDescription(
                5L, "SELECT 1", 0, null, null);
        assertFalse(noColumns.producesResultSet());
        assertTrue(noColumns.parameterDescriptions().isEmpty());
        assertTrue(noColumns.resultColumns().isEmpty());

        EngineApi.PreparedStatementDescription withColumns = new EngineApi.PreparedStatementDescription(
                7L,
                "SELECT amount FROM ledger WHERE id = ?",
                1,
                List.of(new EngineApi.ParameterDescription(1, Common.DataType.INTEGER, 10, 0, true)),
                List.of(new Common.ResultColumn("amount", Common.DataType.DECIMAL, 12, 2)));
        assertTrue(withColumns.producesResultSet());
        assertEquals(Common.DataType.INTEGER, withColumns.parameterDescriptions().getFirst().type());
        assertEquals(12, withColumns.resultColumns().getFirst().precision());
        assertEquals(2, withColumns.resultColumns().getFirst().scale());

        assertThrows(IllegalArgumentException.class,
                () -> new EngineApi.PreparedStatementDescription(-1L, "SELECT 1", 0, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new EngineApi.PreparedStatementDescription(1L, "SELECT 1", -1, List.of(), List.of()));
        assertThrows(NullPointerException.class,
                () -> new EngineApi.PreparedStatementDescription(1L, null, 0, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new EngineApi.PreparedStatementDescription(1L, "SELECT ?", 1, List.of(), List.of()));
    }
}
