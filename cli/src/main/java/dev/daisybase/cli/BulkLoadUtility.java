package dev.daisybase.cli;

import dev.daisybase.common.Common;
import dev.daisybase.engine.EngineApi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class BulkLoadUtility {
    private BulkLoadUtility() {
    }

    static boolean isDirectCommand(String[] args) {
        return args.length > 0 && "load".equalsIgnoreCase(args[0]);
    }

    static LoadCommand parseCommand(String[] args) {
        Objects.requireNonNull(args, "args");
        if (!isDirectCommand(args)) {
            throw new IllegalArgumentException("Expected CLI load command");
        }
        Path configPath = null;
        Path home = null;
        Path file = null;
        String sql = null;
        char delimiter = ',';
        char quote = '"';
        boolean header = false;
        int batchSize = 1_000;
        List<String> nullTokens = new ArrayList<>();
        nullTokens.add("NULL");
        LiteralMode literalMode = LiteralMode.STRING;
        Charset charset = StandardCharsets.UTF_8;
        int maxErrors = 0;
        boolean trimFields = false;
        for (int index = 1; index < args.length; index++) {
            String argument = args[index];
            switch (argument) {
                case "--config" -> configPath = Path.of(requireValue(argument, args, ++index));
                case "--home" -> home = Path.of(requireValue(argument, args, ++index));
                case "--file" -> file = Path.of(requireValue(argument, args, ++index));
                case "--sql" -> sql = requireValue(argument, args, ++index);
                case "--delimiter" -> delimiter = parseSingleCharacter(argument, requireValue(argument, args, ++index));
                case "--quote" -> quote = parseSingleCharacter(argument, requireValue(argument, args, ++index));
                case "--header" -> header = true;
                case "--batch-size" -> batchSize = parsePositiveInt(argument, requireValue(argument, args, ++index));
                case "--null-token" -> nullTokens.add(requireValue(argument, args, ++index));
                case "--literal-mode" -> literalMode = LiteralMode.parse(requireValue(argument, args, ++index));
                case "--charset" -> charset = Charset.forName(requireValue(argument, args, ++index));
                case "--max-errors" -> maxErrors = parseNonNegativeInt(argument, requireValue(argument, args, ++index));
                case "--trim-fields" -> trimFields = true;
                default -> throw new IllegalArgumentException("Unknown bulk-load argument: " + argument);
            }
        }
        if (configPath != null && home != null) {
            throw new IllegalArgumentException("Use either --config or --home, not both");
        }
        if (file == null) {
            throw new IllegalArgumentException("Bulk load requires --file");
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Bulk load requires --sql");
        }
        if (delimiter == quote) {
            throw new IllegalArgumentException("Delimiter and quote must differ");
        }
        return new LoadCommand(configPath, home,
                new BulkLoadRequest(file, sql, delimiter, quote, header, batchSize,
                        Set.copyOf(new LinkedHashSet<>(nullTokens)), literalMode, charset, maxErrors, trimFields));
    }

    static BulkLoadResult execute(EngineApi.Session session, BulkLoadRequest request) throws IOException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");
        long startedAtNanos = System.nanoTime();
        EngineApi.PreparedStatementDescription prepared = session.prepare(request.sql());
        validatePreparedStatement(prepared);
        EngineApi.TransactionHandle transaction = session.transaction();
        boolean ownsTransaction = !transaction.active();
        long rowsRead = 0;
        long rowsLoaded = 0;
        long batchCommits = 0;
        long loadedSinceCommit = 0;
        List<RowError> errors = new ArrayList<>();
        if (ownsTransaction) {
            transaction.begin(Common.IsolationLevel.READ_COMMITTED);
        }
        try (DelimitedDataReader reader = new DelimitedDataReader(
                Files.newBufferedReader(request.source(), request.charset()), request.delimiter(), request.quote())) {
            if (request.header()) {
                reader.readRow();
            }
            DelimitedDataReader.ParsedRow parsedRow;
            while ((parsedRow = reader.readRow()) != null) {
                rowsRead++;
                String savepointName = "bulk_load_row_" + rowsRead;
                transaction.savepoint(savepointName);
                try {
                    List<String> parameterLiterals = toParameterLiterals(parsedRow, request, prepared.parameterCount());
                    EngineApi.BatchResult execution = session.executePrepared(
                            prepared.statementId(), parameterLiterals, Common.ExecutionControl.none());
                    long updated = aggregateUpdateCount(execution);
                    rowsLoaded += updated;
                    loadedSinceCommit += updated;
                    if (ownsTransaction && loadedSinceCommit >= request.batchSize()) {
                        transaction.commit();
                        batchCommits++;
                        loadedSinceCommit = 0;
                        transaction.begin(Common.IsolationLevel.READ_COMMITTED);
                    }
                } catch (RuntimeException exception) {
                    transaction.rollbackToSavepoint(savepointName);
                    errors.add(new RowError(rowsRead, parsedRow.lineNumber(), exception.getMessage()));
                    if (errors.size() > request.maxErrors()) {
                        throw failure("Bulk load aborted after exceeding the configured error threshold",
                                request, rowsRead, rowsLoaded, batchCommits, startedAtNanos, errors, exception);
                    }
                }
            }
            if (ownsTransaction && transaction.active()) {
                if (loadedSinceCommit > 0) {
                    transaction.commit();
                    batchCommits++;
                } else {
                    transaction.rollback();
                }
            }
            return new BulkLoadResult(request.source(), rowsRead, rowsLoaded, errors.size(), batchCommits,
                    durationMillis(startedAtNanos), List.copyOf(errors));
        } catch (IOException | RuntimeException exception) {
            if (ownsTransaction && transaction.active()) {
                transaction.rollback();
            }
            if (exception instanceof BulkLoadException bulkLoadException) {
                throw bulkLoadException;
            }
            throw failure("Bulk load failed", request, rowsRead, rowsLoaded, batchCommits, startedAtNanos, errors, exception);
        } finally {
            session.closePrepared(prepared.statementId());
        }
    }

    static String renderSummary(BulkLoadResult result) {
        return "BULK LOAD source=" + result.source()
                + " rowsRead=" + result.rowsRead()
                + " rowsLoaded=" + result.rowsLoaded()
                + " rowsFailed=" + result.rowsFailed()
                + " batchCommits=" + result.batchCommits()
                + " durationMs=" + result.durationMillis();
    }

    private static void validatePreparedStatement(EngineApi.PreparedStatementDescription prepared) {
        if (prepared.parameterCount() <= 0) {
            throw new IllegalArgumentException("Bulk load SQL must contain at least one parameter placeholder");
        }
        if (prepared.producesResultSet()) {
            throw new IllegalArgumentException("Bulk load SQL must not produce a result set");
        }
    }

    private static List<String> toParameterLiterals(DelimitedDataReader.ParsedRow row,
                                                    BulkLoadRequest request,
                                                    int expectedParameterCount) {
        if (row.fields().size() != expectedParameterCount) {
            throw new IllegalArgumentException("Expected " + expectedParameterCount + " columns but found "
                    + row.fields().size() + " on source line " + row.lineNumber());
        }
        List<String> parameterLiterals = new ArrayList<>(row.fields().size());
        for (String field : row.fields()) {
            parameterLiterals.add(toLiteral(field, request));
        }
        return parameterLiterals;
    }

    private static String toLiteral(String field, BulkLoadRequest request) {
        String effective = request.trimFields() ? field.trim() : field;
        if (request.nullTokens().contains(effective)) {
            return "NULL";
        }
        return switch (request.literalMode()) {
            case STRING -> quoteTextLiteral(effective);
            case RAW -> effective;
            case AUTO -> autoLiteral(effective);
        };
    }

    private static String autoLiteral(String field) {
        String candidate = field.trim();
        if (candidate.isEmpty()) {
            return quoteTextLiteral(field);
        }
        if (candidate.equalsIgnoreCase("true") || candidate.equalsIgnoreCase("false")) {
            return candidate.toUpperCase(Locale.ROOT);
        }
        if (candidate.matches("[-+]?\\d+(\\.\\d+)?")) {
            return candidate;
        }
        if (candidate.startsWith("DATE '")
                || candidate.startsWith("TIME '")
                || candidate.startsWith("TIMESTAMP '")
                || candidate.startsWith("BLOB_FROM_BASE64(")
                || candidate.startsWith("ARRAY_PARSE(")
                || candidate.startsWith("STRUCT_PARSE(")
                || candidate.startsWith("REF_PARSE(")
                || candidate.startsWith("ROWID_FROM_BASE64(")
                || candidate.startsWith("XMLPARSE(")) {
            return candidate;
        }
        return quoteTextLiteral(field);
    }

    private static String quoteTextLiteral(String field) {
        return "'" + field.replace("'", "''") + "'";
    }

    private static long aggregateUpdateCount(EngineApi.BatchResult result) {
        long total = 0;
        for (EngineApi.StatementResult statement : result.statements()) {
            total += Math.max(statement.updateCount(), 0L);
        }
        return total;
    }

    private static BulkLoadException failure(String message,
                                             BulkLoadRequest request,
                                             long rowsRead,
                                             long rowsLoaded,
                                             long batchCommits,
                                             long startedAtNanos,
                                             List<RowError> errors,
                                             Throwable cause) {
        BulkLoadResult result = new BulkLoadResult(request.source(), rowsRead, rowsLoaded, errors.size(), batchCommits,
                durationMillis(startedAtNanos), List.copyOf(errors));
        return new BulkLoadException(message, cause, result);
    }

    private static long durationMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private static String requireValue(String option, String[] args, int index) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static char parseSingleCharacter(String option, String value) {
        if (value.length() != 1) {
            throw new IllegalArgumentException(option + " expects a single character value");
        }
        return value.charAt(0);
    }

    private static int parsePositiveInt(String option, String value) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(option + " must be greater than zero");
        }
        return parsed;
    }

    private static int parseNonNegativeInt(String option, String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException(option + " must be non-negative");
        }
        return parsed;
    }

    enum LiteralMode {
        STRING,
        RAW,
        AUTO;

        static LiteralMode parse(String text) {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        }
    }

    record LoadCommand(Path configPath, Path home, BulkLoadRequest request) {
    }

    record BulkLoadRequest(Path source, String sql, char delimiter, char quote, boolean header, int batchSize,
                           Set<String> nullTokens, LiteralMode literalMode, Charset charset, int maxErrors,
                           boolean trimFields) {
        BulkLoadRequest {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(sql, "sql");
            Objects.requireNonNull(nullTokens, "nullTokens");
            Objects.requireNonNull(literalMode, "literalMode");
            Objects.requireNonNull(charset, "charset");
            if (sql.isBlank()) {
                throw new IllegalArgumentException("sql must not be blank");
            }
            if (batchSize <= 0) {
                throw new IllegalArgumentException("batchSize must be greater than zero");
            }
            if (maxErrors < 0) {
                throw new IllegalArgumentException("maxErrors must be non-negative");
            }
            nullTokens = Set.copyOf(nullTokens);
        }
    }

    record BulkLoadResult(Path source, long rowsRead, long rowsLoaded, long rowsFailed, long batchCommits,
                          long durationMillis, List<RowError> errors) {
        BulkLoadResult {
            Objects.requireNonNull(source, "source");
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    record RowError(long rowNumber, long lineNumber, String message) {
        RowError {
            message = message == null ? "Unknown bulk-load failure" : message;
        }
    }

    static final class BulkLoadException extends RuntimeException {
        private final BulkLoadResult result;

        BulkLoadException(String message, Throwable cause, BulkLoadResult result) {
            super(message, cause);
            this.result = Objects.requireNonNull(result, "result");
        }

        BulkLoadResult result() {
            return result;
        }
    }

    private static final class DelimitedDataReader implements AutoCloseable {
        private static final int NO_PUSHBACK = Integer.MIN_VALUE;

        private final Reader reader;
        private final char delimiter;
        private final char quote;
        private int pushback = NO_PUSHBACK;
        private long lineNumber = 1;

        private DelimitedDataReader(Reader reader, char delimiter, char quote) {
            this.reader = new BufferedReader(Objects.requireNonNull(reader, "reader"));
            this.delimiter = delimiter;
            this.quote = quote;
        }

        ParsedRow readRow() throws IOException {
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean inQuotes = false;
            boolean rowHasContent = false;
            long rowLine = lineNumber;
            while (true) {
                int next = readCharacter();
                if (next == -1) {
                    if (!rowHasContent && fields.isEmpty() && field.isEmpty()) {
                        return null;
                    }
                    if (inQuotes) {
                        throw new IllegalArgumentException("Unterminated quoted field starting on line " + rowLine);
                    }
                    fields.add(field.toString());
                    return new ParsedRow(rowLine, List.copyOf(fields));
                }
                char character = (char) next;
                if (inQuotes) {
                    if (character == quote) {
                        int escaped = readCharacter();
                        if (escaped == quote) {
                            field.append(quote);
                            rowHasContent = true;
                        } else {
                            unread(escaped);
                            inQuotes = false;
                        }
                        continue;
                    }
                    if (character == '\r') {
                        consumeOptionalLineFeed();
                        lineNumber++;
                        field.append('\n');
                        rowHasContent = true;
                        continue;
                    }
                    if (character == '\n') {
                        lineNumber++;
                        field.append('\n');
                        rowHasContent = true;
                        continue;
                    }
                    field.append(character);
                    rowHasContent = true;
                    continue;
                }
                if (character == quote && field.isEmpty()) {
                    inQuotes = true;
                    rowHasContent = true;
                    continue;
                }
                if (character == delimiter) {
                    fields.add(field.toString());
                    field.setLength(0);
                    rowHasContent = true;
                    continue;
                }
                if (character == '\r') {
                    consumeOptionalLineFeed();
                    lineNumber++;
                    if (!rowHasContent && fields.isEmpty() && field.isEmpty()) {
                        rowLine = lineNumber;
                        continue;
                    }
                    fields.add(field.toString());
                    return new ParsedRow(rowLine, List.copyOf(fields));
                }
                if (character == '\n') {
                    lineNumber++;
                    if (!rowHasContent && fields.isEmpty() && field.isEmpty()) {
                        rowLine = lineNumber;
                        continue;
                    }
                    fields.add(field.toString());
                    return new ParsedRow(rowLine, List.copyOf(fields));
                }
                field.append(character);
                rowHasContent = true;
            }
        }

        private int readCharacter() throws IOException {
            if (pushback != NO_PUSHBACK) {
                int value = pushback;
                pushback = NO_PUSHBACK;
                return value;
            }
            return reader.read();
        }

        private void unread(int value) {
            if (value == -1) {
                return;
            }
            if (pushback != NO_PUSHBACK) {
                throw new IllegalStateException("Only one character of pushback is supported");
            }
            pushback = value;
        }

        private void consumeOptionalLineFeed() throws IOException {
            int next = readCharacter();
            if (next != '\n') {
                unread(next);
            }
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }

        record ParsedRow(long lineNumber, List<String> fields) {
        }
    }
}
