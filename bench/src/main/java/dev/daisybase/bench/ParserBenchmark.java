package dev.daisybase.bench;

import dev.daisybase.sql.SqlFrontend;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class ParserBenchmark {
    private final String sql = "SELECT id, name FROM public.users WHERE id = 42 ORDER BY name;";

    @Benchmark
    public Object parseSelect() {
        return SqlFrontend.parseBatch(sql);
    }
}