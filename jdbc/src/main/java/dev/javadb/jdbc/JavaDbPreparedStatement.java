package dev.javadb.jdbc;

import dev.javadb.engine.EngineApi;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class JavaDbPreparedStatement extends JavaDbStatement implements PreparedStatement {
    private final String sql;
    private final JavaDbPreparedSql preparedSql;
    private final JavaDbTransport.PreparedHandle preparedHandle;
    private final List<JavaDbPreparedSql.BoundParameter> parameters;
    private final boolean returnGeneratedKeys;

    JavaDbPreparedStatement(JavaDbConnection connection, int resultSetType, int resultSetConcurrency,
                            String sql, boolean returnGeneratedKeys) throws SQLException {
        super(connection, resultSetType, resultSetConcurrency);
        this.sql = sql;
        this.preparedSql = JavaDbPreparedSql.parse(sql);
        this.preparedHandle = connection.prepareSql(sql);
        this.parameters = new ArrayList<>(Collections.nCopies(preparedSql.parameterCount(), null));
        this.returnGeneratedKeys = returnGeneratedKeys;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        runPrepared(returnGeneratedKeys);
        ResultSet resultSet = getResultSet();
        if (resultSet == null) {
            throw new SQLException("SQL did not produce a result set");
        }
        return resultSet;
    }

    @Override
    public int executeUpdate() throws SQLException {
        long count = executeLargeUpdate();
        if (count > Integer.MAX_VALUE) {
            throw new SQLException("Update count exceeds Integer.MAX_VALUE");
        }
        return (int) count;
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        runPrepared(returnGeneratedKeys);
        ResultSet resultSet = getResultSet();
        if (resultSet != null) {
            throw new SQLException("SQL produced a result set, not an update count");
        }
        return Math.max(getLargeUpdateCount(), 0L);
    }

    @Override
    public boolean execute() throws SQLException {
        return runPrepared(returnGeneratedKeys);
    }

    @Override
    public void addBatch() throws SQLException {
        super.addBatch(renderedSql());
    }

    @Override
    public void clearParameters() {
        for (int index = 0; index < parameters.size(); index++) {
            parameters.set(index, null);
        }
    }

    @Override
    public ParameterMetaData getParameterMetaData() {
        return JavaDbParameterMetaData.create(preparedHandle.parameterDescriptions(), visibleParameterCount());
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        bind(parameterIndex, null, sqlType);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        bind(parameterIndex, x, Types.BOOLEAN);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        bind(parameterIndex, x, Types.INTEGER);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        bind(parameterIndex, x, Types.BIGINT);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        bind(parameterIndex, x, Types.VARCHAR);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        bind(parameterIndex, x, Types.NUMERIC);
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        bind(parameterIndex, x, null);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        bind(parameterIndex, x, targetSqlType);
    }

    @Override
    public void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException {
        bind(parameterIndex, x, targetSqlType == null ? null : targetSqlType.getVendorTypeNumber());
    }

    @Override
    public void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
        bind(parameterIndex, x, targetSqlType == null ? null : targetSqlType.getVendorTypeNumber());
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        bind(parameterIndex, x, targetSqlType);
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        throw new SQLException("Use executeQuery() on PreparedStatement instances");
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        throw new SQLException("Use executeUpdate() on PreparedStatement instances");
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        throw new SQLException("Use execute() on PreparedStatement instances");
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        throw new SQLException("Use executeLargeUpdate() on PreparedStatement instances");
    }

    @Override
    public java.sql.ResultSetMetaData getMetaData() throws SQLException {
        if (preparedHandle.resultColumns().isEmpty()) {
            return null;
        }
        return JavaDbResultSets.metaData(preparedHandle.resultColumns());
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        bind(parameterIndex, x, Types.TINYINT);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        bind(parameterIndex, x, Types.SMALLINT);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        bind(parameterIndex, x, Types.FLOAT);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        bind(parameterIndex, x, Types.DOUBLE);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        bind(parameterIndex, x, Types.VARCHAR);
    }

    @Override
    public void setDate(int parameterIndex, java.sql.Date x) throws SQLException {
        bind(parameterIndex, x, Types.DATE);
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        bind(parameterIndex, x, Types.TIME);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        bind(parameterIndex, x, Types.TIMESTAMP);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        bind(parameterIndex, readAscii(x), Types.VARCHAR);
    }

    @Override
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw unsupportedType("UnicodeStream");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        bind(parameterIndex, JavaDbJdbcObjects.readBinary(x), Types.VARCHAR);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        bind(parameterIndex, readText(reader), Types.VARCHAR);
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        bind(parameterIndex, x, Types.VARCHAR);
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        bind(parameterIndex, x, Types.VARCHAR);
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        bind(parameterIndex, x == null ? null : readText(x.getCharacterStream()), Types.VARCHAR);
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        bind(parameterIndex, x, Types.VARCHAR);
    }

    @Override
    public void setDate(int parameterIndex, java.sql.Date x, java.util.Calendar cal) throws SQLException {
        bind(parameterIndex, x == null ? null : x.toLocalDate(), Types.DATE);
    }

    @Override
    public void setTime(int parameterIndex, Time x, java.util.Calendar cal) throws SQLException {
        bind(parameterIndex, x == null ? null : x.toLocalTime(), Types.TIME);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, java.util.Calendar cal) throws SQLException {
        bind(parameterIndex, x == null ? null : x.toLocalDateTime(), Types.TIMESTAMP);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        bind(parameterIndex, null, sqlType);
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        bind(parameterIndex, x, Types.VARCHAR);
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        bind(parameterIndex, x, Types.VARCHAR);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        bind(parameterIndex, value, Types.VARCHAR);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        bind(parameterIndex, readText(value), Types.VARCHAR);
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        bind(parameterIndex, value == null ? null : readText(value.getCharacterStream()), Types.VARCHAR);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        bind(parameterIndex, readText(reader), Types.VARCHAR);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        bind(parameterIndex, JavaDbJdbcObjects.readBinary(inputStream), Types.VARCHAR);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        bind(parameterIndex, readText(reader), Types.VARCHAR);
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        bind(parameterIndex, xmlObject, Types.SQLXML);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        bind(parameterIndex, readAscii(x), Types.VARCHAR);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        bind(parameterIndex, JavaDbJdbcObjects.readBinary(x), Types.VARCHAR);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        bind(parameterIndex, readText(reader), Types.VARCHAR);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        bind(parameterIndex, readAscii(x), Types.VARCHAR);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        bind(parameterIndex, JavaDbJdbcObjects.readBinary(x), Types.VARCHAR);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        bind(parameterIndex, readText(reader), Types.VARCHAR);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        bind(parameterIndex, readText(value), Types.VARCHAR);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        bind(parameterIndex, readText(reader), Types.VARCHAR);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        bind(parameterIndex, JavaDbJdbcObjects.readBinary(inputStream), Types.VARCHAR);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        bind(parameterIndex, readText(reader), Types.VARCHAR);
    }

    protected void bind(int parameterIndex, Object value, Integer sqlType) throws SQLException {
        int internalIndex = normalizeParameterIndex(parameterIndex);
        if (internalIndex < 1 || internalIndex > parameters.size()) {
            throw new SQLException("Parameter index out of range: " + parameterIndex);
        }
        parameters.set(internalIndex - 1, JavaDbPreparedSql.BoundParameter.of(
                JavaDbJdbcObjects.normalizeParameterValue(value), sqlType));
    }

    protected String renderedSql() throws SQLException {
        return preparedSql.render(parameters);
    }

    protected String renderParameters(List<JavaDbPreparedSql.BoundParameter> boundParameters) throws SQLException {
        return preparedSql.render(boundParameters);
    }

    protected List<String> renderedParameterLiterals() throws SQLException {
        return preparedSql.renderLiterals(parameters);
    }

    protected List<JavaDbPreparedSql.BoundParameter> parameterValues() {
        return parameters;
    }

    protected int visibleParameterCount() {
        return preparedSql.parameterCount();
    }

    protected int normalizeParameterIndex(int parameterIndex) throws SQLException {
        return parameterIndex;
    }

    @Override
    public void close() throws SQLException {
        SQLException firstFailure = null;
        try {
            connection.closePrepared(preparedHandle.statementId());
        } catch (SQLException sqlException) {
            firstFailure = sqlException;
        }
        try {
            super.close();
        } catch (SQLException sqlException) {
            if (firstFailure == null) {
                firstFailure = sqlException;
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private boolean runPrepared(boolean requestGeneratedKeys) throws SQLException {
        prepareForExecution();
        long executionId = connection.nextExecutionId();
        setCurrentExecutionId(executionId);
        try {
            EngineApi.BatchResult batchResult = connection.executePrepared(
                    preparedHandle.statementId(), renderedParameterLiterals(), executionId, timeoutMillis());
            completeExecution(materializeBatchResult(batchResult, requestGeneratedKeys, sql));
            return getResultSet() != null;
        } finally {
            setCurrentExecutionId(-1L);
        }
    }

    private SQLFeatureNotSupportedException unsupportedType(String type) {
        return new SQLFeatureNotSupportedException(type + " parameters are not supported");
    }

    private String readAscii(InputStream inputStream) throws SQLException {
        if (inputStream == null) {
            return null;
        }
        return readText(new InputStreamReader(inputStream, StandardCharsets.US_ASCII));
    }

    private String readText(Reader reader) throws SQLException {
        if (reader == null) {
            return null;
        }
        try {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
            return text.toString();
        } catch (IOException exception) {
            throw new SQLException("Failed reading parameter stream", exception);
        }
    }
}
