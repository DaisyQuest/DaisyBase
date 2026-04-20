package dev.daisybase.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaisyBaseCallableSqlTest {
    @Test
    void parsesProcedureAndFunctionCallSyntax() throws Exception {
        DaisyBaseCallableSql procedure = DaisyBaseCallableSql.parse("{call public.finish_job(?, ?, ?)}");
        assertEquals("CALL public.finish_job(?,?,?)", procedure.nativeSql());
        assertEquals(3, procedure.parameterCount());
        assertTrue(!procedure.hasReturnValue());

        DaisyBaseCallableSql function = DaisyBaseCallableSql.parse("{? = call public.add_fee(?)}");
        assertEquals("CALL public.add_fee(?)", function.nativeSql());
        assertEquals(2, function.parameterCount());
        assertTrue(function.hasReturnValue());
        assertEquals(1, function.toInternalParameterIndex(2));
    }

    @Test
    void rejectsCallableSqlWithoutQuestionMarkersForRoutineArguments() {
        assertThrows(SQLException.class, () -> DaisyBaseCallableSql.parse("{call public.finish_job(1, ?, ?)}"));
        assertThrows(SQLException.class, () -> DaisyBaseCallableSql.parse("{? call public.add_fee(?)}"));
    }
}
