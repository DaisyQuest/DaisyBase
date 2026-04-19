package dev.javadb.jdbc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaDbPreparedSqlTest {
    @Test
    void rendersBoundParametersWithEscapingAndNulls() throws SQLException {
        JavaDbPreparedSql preparedSql = JavaDbPreparedSql.parse(
                "SELECT * FROM users WHERE id = ? AND name = ? AND enabled = ? AND note = ?");

        String rendered = preparedSql.render(List.of(
                JavaDbPreparedSql.BoundParameter.of(7, Types.INTEGER),
                JavaDbPreparedSql.BoundParameter.of("Ada's", Types.VARCHAR),
                JavaDbPreparedSql.BoundParameter.of(true, Types.BOOLEAN),
                JavaDbPreparedSql.BoundParameter.of(null, Types.VARCHAR)
        ));

        assertEquals("SELECT * FROM users WHERE id = 7 AND name = 'Ada''s' AND enabled = TRUE AND note = NULL", rendered);
    }

    @Test
    void rejectsMultiStatementPreparedSql() {
        assertThrows(SQLException.class, () -> JavaDbPreparedSql.parse("SELECT 1; SELECT 2;"));
    }

    @Test
    void rendersScaledBigDecimalParametersAsNumericLiterals() throws Exception {
        JavaDbPreparedSql preparedSql = JavaDbPreparedSql.parse("INSERT INTO ledger VALUES (?)");

        String rendered = preparedSql.render(List.of(
                JavaDbPreparedSql.BoundParameter.of(new BigDecimal("12.50"), Types.NUMERIC)
        ));

        assertEquals("INSERT INTO ledger VALUES (12.50)", rendered);
    }

    @Test
    void rendersTemporalAndUrlParametersAsTypedLiterals() throws Exception {
        JavaDbPreparedSql preparedSql = JavaDbPreparedSql.parse(
                "INSERT INTO events VALUES (?, ?, ?, ?)");

        String rendered = preparedSql.render(List.of(
                JavaDbPreparedSql.BoundParameter.of(Date.valueOf("2026-04-19"), Types.VARCHAR),
                JavaDbPreparedSql.BoundParameter.of(Time.valueOf("10:15:30"), Types.VARCHAR),
                JavaDbPreparedSql.BoundParameter.of(Timestamp.valueOf("2026-04-19 10:15:30"), Types.VARCHAR),
                JavaDbPreparedSql.BoundParameter.of(URI.create("https://example.com/docs").toURL(), Types.VARCHAR)
        ));

        assertEquals(
                "INSERT INTO events VALUES (DATE '2026-04-19', TIME '10:15:30', TIMESTAMP '2026-04-19T10:15:30', 'https://example.com/docs')",
                rendered);
    }
}
