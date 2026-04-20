package dev.daisybase.jdbc;

import dev.daisybase.common.Common;
import dev.daisybase.engine.EngineApi;
import dev.daisybase.engine.EngineIntrospection;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DaisyBaseCallableStatement extends DaisyBasePreparedStatement implements CallableStatement {
    private final DaisyBaseCallableSql callableSql;
    private final CallableSignature signature;
    private final Map<Integer, Integer> registeredOutParameters = new LinkedHashMap<>();
    private final Map<Integer, Common.Value> outputValues = new HashMap<>();
    private boolean lastWasNull;

    DaisyBaseCallableStatement(DaisyBaseConnection connection, int resultSetType, int resultSetConcurrency,
                            String sql) throws SQLException {
        this(connection, resultSetType, resultSetConcurrency, DaisyBaseCallableSql.parse(sql));
    }

    private DaisyBaseCallableStatement(DaisyBaseConnection connection, int resultSetType, int resultSetConcurrency,
                                    DaisyBaseCallableSql callableSql) throws SQLException {
        super(connection, resultSetType, resultSetConcurrency, callableSql.nativeSql(), false);
        this.callableSql = callableSql;
        this.signature = CallableSignature.load(connection, callableSql);
    }

    @Override
    protected int visibleParameterCount() {
        return callableSql.parameterCount();
    }

    @Override
    protected int normalizeParameterIndex(int parameterIndex) throws SQLException {
        return callableSql.toInternalParameterIndex(parameterIndex);
    }

    @Override
    public ParameterMetaData getParameterMetaData() {
        return DaisyBaseParameterMetaData.create(signature.parameterDescriptors());
    }

    @Override
    protected String renderedSql() throws SQLException {
        List<DaisyBasePreparedSql.BoundParameter> parameters = new ArrayList<>(parameterValues());
        for (CallableParameter parameter : signature.parameters()) {
            if (parameters.get(parameter.ordinal() - 1) == null) {
                if (parameter.outputOnly()) {
                    parameters.set(parameter.ordinal() - 1, DaisyBasePreparedSql.BoundParameter.of(null, parameter.jdbcType()));
                } else {
                    throw new SQLException("Parameter " + callableSql.toExternalParameterIndex(parameter.ordinal() - 1) + " is not bound");
                }
            }
        }
        return renderParameters(parameters);
    }

    @Override
    protected List<String> renderedParameterLiterals() throws SQLException {
        List<DaisyBasePreparedSql.BoundParameter> parameters = new ArrayList<>(parameterValues());
        for (CallableParameter parameter : signature.parameters()) {
            if (parameters.get(parameter.ordinal() - 1) == null) {
                if (parameter.outputOnly()) {
                    parameters.set(parameter.ordinal() - 1, DaisyBasePreparedSql.BoundParameter.of(null, parameter.jdbcType()));
                } else {
                    throw new SQLException("Parameter " + callableSql.toExternalParameterIndex(parameter.ordinal() - 1) + " is not bound");
                }
            }
        }
        return DaisyBasePreparedSql.parse(callableSql.nativeSql()).renderLiterals(parameters);
    }

    @Override
    protected BatchMaterialization materializeBatchResult(EngineApi.BatchResult batchResult,
                                                          boolean requestGeneratedKeys,
                                                          String sql) throws SQLException {
        outputValues.clear();
        lastWasNull = false;
        if (batchResult.statements().isEmpty()) {
            return new BatchMaterialization(List.of(), Common.TupleBatch.empty());
        }
        EngineApi.StatementResult statement = batchResult.statements().getFirst();
        captureOutputs(statement.batch());
        return new BatchMaterialization(List.of(new StatementOutcome(null, statement.updateCount(), statement.commandTag())),
                Common.TupleBatch.empty());
    }

    private void captureOutputs(Common.TupleBatch batch) throws SQLException {
        if (signature.function()) {
            if (!batch.rows().isEmpty()) {
                outputValues.put(1, batch.rows().getFirst().get(0));
            }
            return;
        }
        if (batch.rows().isEmpty()) {
            return;
        }
        List<Common.Value> values = batch.rows().getFirst().values();
        int outputColumn = 0;
        for (CallableParameter parameter : signature.parameters()) {
            if (!parameter.output()) {
                continue;
            }
            if (outputColumn >= values.size()) {
                throw new SQLException("Procedure output payload was missing value for parameter " + parameter.name());
            }
            outputValues.put(callableSql.toExternalParameterIndex(parameter.ordinal() - 1), values.get(outputColumn++));
        }
    }

    @Override
    public boolean wasNull() {
        return lastWasNull;
    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType) throws SQLException {
        validateOutParameter(parameterIndex);
        registeredOutParameters.put(parameterIndex, sqlType);
    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
        registerOutParameter(parameterIndex, sqlType);
    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException {
        registerOutParameter(parameterIndex, sqlType);
    }

    @Override
    public void registerOutParameter(String parameterName, int sqlType) throws SQLException {
        registerOutParameter(parameterIndex(parameterName), sqlType);
    }

    @Override
    public void registerOutParameter(String parameterName, int sqlType, int scale) throws SQLException {
        registerOutParameter(parameterIndex(parameterName), sqlType, scale);
    }

    @Override
    public void registerOutParameter(String parameterName, int sqlType, String typeName) throws SQLException {
        registerOutParameter(parameterIndex(parameterName), sqlType, typeName);
    }

    @Override
    public void registerOutParameter(int parameterIndex, SQLType sqlType) throws SQLException {
        registerOutParameter(parameterIndex, sqlType == null ? Types.NULL : sqlType.getVendorTypeNumber());
    }

    @Override
    public void registerOutParameter(int parameterIndex, SQLType sqlType, int scale) throws SQLException {
        registerOutParameter(parameterIndex, sqlType);
    }

    @Override
    public void registerOutParameter(int parameterIndex, SQLType sqlType, String typeName) throws SQLException {
        registerOutParameter(parameterIndex, sqlType);
    }

    @Override
    public void registerOutParameter(String parameterName, SQLType sqlType) throws SQLException {
        registerOutParameter(parameterIndex(parameterName), sqlType);
    }

    @Override
    public void registerOutParameter(String parameterName, SQLType sqlType, int scale) throws SQLException {
        registerOutParameter(parameterIndex(parameterName), sqlType, scale);
    }

    @Override
    public void registerOutParameter(String parameterName, SQLType sqlType, String typeName) throws SQLException {
        registerOutParameter(parameterIndex(parameterName), sqlType, typeName);
    }

    @Override
    public String getString(int parameterIndex) throws SQLException {
        return output(parameterIndex).asText();
    }

    @Override
    public boolean getBoolean(int parameterIndex) throws SQLException {
        return output(parameterIndex).asBoolean();
    }

    @Override
    public byte getByte(int parameterIndex) throws SQLException {
        return (byte) output(parameterIndex).asInt();
    }

    @Override
    public short getShort(int parameterIndex) throws SQLException {
        return (short) output(parameterIndex).asInt();
    }

    @Override
    public int getInt(int parameterIndex) throws SQLException {
        return output(parameterIndex).asInt();
    }

    @Override
    public long getLong(int parameterIndex) throws SQLException {
        return output(parameterIndex).asLong();
    }

    @Override
    public float getFloat(int parameterIndex) throws SQLException {
        return output(parameterIndex).asDecimal().floatValue();
    }

    @Override
    public double getDouble(int parameterIndex) throws SQLException {
        return output(parameterIndex).asDecimal().doubleValue();
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int parameterIndex, int scale) throws SQLException {
        return output(parameterIndex).asDecimal().setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public byte[] getBytes(int parameterIndex) throws SQLException {
        Common.Value value = output(parameterIndex);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.type() == Common.DataType.BLOB) {
            return value.asBytes();
        }
        if (value.type() == Common.DataType.ROWID) {
            return value.asRowIdBytes();
        }
        return DaisyBaseJdbcObjects.toBinary(DaisyBaseJdbcObjects.requireTextValue(value, "byte[] outputs"));
    }

    @Override
    public Date getDate(int parameterIndex) throws SQLException {
        return Date.valueOf(output(parameterIndex).asDate());
    }

    @Override
    public Time getTime(int parameterIndex) throws SQLException {
        return Time.valueOf(output(parameterIndex).asTime());
    }

    @Override
    public Timestamp getTimestamp(int parameterIndex) throws SQLException {
        return Timestamp.valueOf(output(parameterIndex).asTimestamp());
    }

    @Override
    public Object getObject(int parameterIndex) throws SQLException {
        return toJdbcValue(output(parameterIndex));
    }

    @Override
    public BigDecimal getBigDecimal(int parameterIndex) throws SQLException {
        return output(parameterIndex).asDecimal();
    }

    @Override
    public Object getObject(int parameterIndex, Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw unsupported("custom type maps");
        }
        return getObject(parameterIndex);
    }

    @Override
    public Ref getRef(int parameterIndex) throws SQLException {
        return DaisyBaseJdbcObjects.toRef(output(parameterIndex));
    }

    @Override
    public Blob getBlob(int parameterIndex) throws SQLException {
        return DaisyBaseJdbcObjects.toBlob(output(parameterIndex));
    }

    @Override
    public Clob getClob(int parameterIndex) throws SQLException {
        return DaisyBaseJdbcObjects.toClob(DaisyBaseJdbcObjects.requireTextValue(output(parameterIndex), "Clob outputs"));
    }

    @Override
    public Array getArray(int parameterIndex) throws SQLException {
        return DaisyBaseJdbcObjects.toArray(output(parameterIndex));
    }

    @Override
    public Date getDate(int parameterIndex, Calendar cal) throws SQLException {
        return getDate(parameterIndex);
    }

    @Override
    public Time getTime(int parameterIndex, Calendar cal) throws SQLException {
        return getTime(parameterIndex);
    }

    @Override
    public Timestamp getTimestamp(int parameterIndex, Calendar cal) throws SQLException {
        return getTimestamp(parameterIndex);
    }

    @Override
    public URL getURL(int parameterIndex) throws SQLException {
        try {
            return new URL(output(parameterIndex).asText());
        } catch (Exception exception) {
            throw new SQLException("Invalid URL value", exception);
        }
    }

    @Override
    public RowId getRowId(int parameterIndex) throws SQLException {
        return DaisyBaseJdbcObjects.toRowId(output(parameterIndex));
    }

    @Override
    public NClob getNClob(int parameterIndex) throws SQLException {
        return DaisyBaseJdbcObjects.toNClob(DaisyBaseJdbcObjects.requireTextValue(output(parameterIndex), "NClob outputs"));
    }

    @Override
    public SQLXML getSQLXML(int parameterIndex) throws SQLException {
        return DaisyBaseJdbcObjects.toSqlXml(output(parameterIndex));
    }

    @Override
    public String getNString(int parameterIndex) throws SQLException {
        return getString(parameterIndex);
    }

    @Override
    public Reader getNCharacterStream(int parameterIndex) throws SQLException {
        return DaisyBaseJdbcObjects.toCharacterStream(
                DaisyBaseJdbcObjects.requireTextValue(output(parameterIndex), "NCharacterStream outputs"));
    }

    @Override
    public Reader getCharacterStream(int parameterIndex) throws SQLException {
        return DaisyBaseJdbcObjects.toCharacterStream(
                DaisyBaseJdbcObjects.requireTextValue(output(parameterIndex), "CharacterStream outputs"));
    }

    @Override
    public <T> T getObject(int parameterIndex, Class<T> type) throws SQLException {
        if (type == null) {
            throw new SQLException("type must not be null");
        }
        Common.Value output = output(parameterIndex);
        if (output == null || output.isNull()) {
            return null;
        }
        if (type == Clob.class) {
            return type.cast(getClob(parameterIndex));
        }
        if (type == NClob.class) {
            return type.cast(getNClob(parameterIndex));
        }
        if (type == SQLXML.class) {
            return type.cast(getSQLXML(parameterIndex));
        }
        if (type == Blob.class) {
            return type.cast(getBlob(parameterIndex));
        }
        if (type == Array.class) {
            return type.cast(getArray(parameterIndex));
        }
        if (type == java.sql.Struct.class) {
            return type.cast(DaisyBaseJdbcObjects.toStruct(output(parameterIndex)));
        }
        if (type == Ref.class) {
            return type.cast(getRef(parameterIndex));
        }
        if (type == RowId.class) {
            return type.cast(getRowId(parameterIndex));
        }
        Object value = toJdbcValue(output);
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        throw new SQLException("Output parameter " + parameterIndex + " is not assignable to " + type.getName());
    }

    @Override
    public String getString(String parameterName) throws SQLException {
        return getString(parameterIndex(parameterName));
    }

    @Override
    public boolean getBoolean(String parameterName) throws SQLException {
        return getBoolean(parameterIndex(parameterName));
    }

    @Override
    public byte getByte(String parameterName) throws SQLException {
        return getByte(parameterIndex(parameterName));
    }

    @Override
    public short getShort(String parameterName) throws SQLException {
        return getShort(parameterIndex(parameterName));
    }

    @Override
    public int getInt(String parameterName) throws SQLException {
        return getInt(parameterIndex(parameterName));
    }

    @Override
    public long getLong(String parameterName) throws SQLException {
        return getLong(parameterIndex(parameterName));
    }

    @Override
    public float getFloat(String parameterName) throws SQLException {
        return getFloat(parameterIndex(parameterName));
    }

    @Override
    public double getDouble(String parameterName) throws SQLException {
        return getDouble(parameterIndex(parameterName));
    }

    @Override
    public byte[] getBytes(String parameterName) throws SQLException {
        return getBytes(parameterIndex(parameterName));
    }

    @Override
    public Date getDate(String parameterName) throws SQLException {
        return getDate(parameterIndex(parameterName));
    }

    @Override
    public Time getTime(String parameterName) throws SQLException {
        return getTime(parameterIndex(parameterName));
    }

    @Override
    public Timestamp getTimestamp(String parameterName) throws SQLException {
        return getTimestamp(parameterIndex(parameterName));
    }

    @Override
    public Object getObject(String parameterName) throws SQLException {
        return getObject(parameterIndex(parameterName));
    }

    @Override
    public BigDecimal getBigDecimal(String parameterName) throws SQLException {
        return getBigDecimal(parameterIndex(parameterName));
    }

    @Override
    public Object getObject(String parameterName, Map<String, Class<?>> map) throws SQLException {
        return getObject(parameterIndex(parameterName), map);
    }

    @Override
    public Ref getRef(String parameterName) throws SQLException {
        return getRef(parameterIndex(parameterName));
    }

    @Override
    public Blob getBlob(String parameterName) throws SQLException {
        return getBlob(parameterIndex(parameterName));
    }

    @Override
    public Clob getClob(String parameterName) throws SQLException {
        return getClob(parameterIndex(parameterName));
    }

    @Override
    public Array getArray(String parameterName) throws SQLException {
        return getArray(parameterIndex(parameterName));
    }

    @Override
    public Date getDate(String parameterName, Calendar cal) throws SQLException {
        return getDate(parameterIndex(parameterName), cal);
    }

    @Override
    public Time getTime(String parameterName, Calendar cal) throws SQLException {
        return getTime(parameterIndex(parameterName), cal);
    }

    @Override
    public Timestamp getTimestamp(String parameterName, Calendar cal) throws SQLException {
        return getTimestamp(parameterIndex(parameterName), cal);
    }

    @Override
    public URL getURL(String parameterName) throws SQLException {
        return getURL(parameterIndex(parameterName));
    }

    @Override
    public RowId getRowId(String parameterName) throws SQLException {
        return getRowId(parameterIndex(parameterName));
    }

    @Override
    public NClob getNClob(String parameterName) throws SQLException {
        return getNClob(parameterIndex(parameterName));
    }

    @Override
    public SQLXML getSQLXML(String parameterName) throws SQLException {
        return getSQLXML(parameterIndex(parameterName));
    }

    @Override
    public String getNString(String parameterName) throws SQLException {
        return getNString(parameterIndex(parameterName));
    }

    @Override
    public Reader getNCharacterStream(String parameterName) throws SQLException {
        return getNCharacterStream(parameterIndex(parameterName));
    }

    @Override
    public Reader getCharacterStream(String parameterName) throws SQLException {
        return getCharacterStream(parameterIndex(parameterName));
    }

    @Override
    public <T> T getObject(String parameterName, Class<T> type) throws SQLException {
        return getObject(parameterIndex(parameterName), type);
    }

    @Override
    public void setURL(String parameterName, URL val) throws SQLException {
        setURL(parameterIndex(parameterName), val);
    }

    @Override
    public void setNull(String parameterName, int sqlType) throws SQLException {
        setNull(parameterIndex(parameterName), sqlType);
    }

    @Override
    public void setBoolean(String parameterName, boolean x) throws SQLException {
        setBoolean(parameterIndex(parameterName), x);
    }

    @Override
    public void setByte(String parameterName, byte x) throws SQLException {
        setByte(parameterIndex(parameterName), x);
    }

    @Override
    public void setShort(String parameterName, short x) throws SQLException {
        setShort(parameterIndex(parameterName), x);
    }

    @Override
    public void setInt(String parameterName, int x) throws SQLException {
        setInt(parameterIndex(parameterName), x);
    }

    @Override
    public void setLong(String parameterName, long x) throws SQLException {
        setLong(parameterIndex(parameterName), x);
    }

    @Override
    public void setFloat(String parameterName, float x) throws SQLException {
        setFloat(parameterIndex(parameterName), x);
    }

    @Override
    public void setDouble(String parameterName, double x) throws SQLException {
        setDouble(parameterIndex(parameterName), x);
    }

    @Override
    public void setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
        setBigDecimal(parameterIndex(parameterName), x);
    }

    @Override
    public void setString(String parameterName, String x) throws SQLException {
        setString(parameterIndex(parameterName), x);
    }

    @Override
    public void setBytes(String parameterName, byte[] x) throws SQLException {
        setBytes(parameterIndex(parameterName), x);
    }

    @Override
    public void setDate(String parameterName, Date x) throws SQLException {
        setDate(parameterIndex(parameterName), x);
    }

    @Override
    public void setTime(String parameterName, Time x) throws SQLException {
        setTime(parameterIndex(parameterName), x);
    }

    @Override
    public void setTimestamp(String parameterName, Timestamp x) throws SQLException {
        setTimestamp(parameterIndex(parameterName), x);
    }

    @Override
    public void setAsciiStream(String parameterName, InputStream x, int length) throws SQLException {
        setAsciiStream(parameterIndex(parameterName), x, length);
    }

    @Override
    public void setBinaryStream(String parameterName, InputStream x, int length) throws SQLException {
        setBinaryStream(parameterIndex(parameterName), x, length);
    }

    @Override
    public void setObject(String parameterName, Object x, int targetSqlType, int scale) throws SQLException {
        setObject(parameterIndex(parameterName), x, targetSqlType, scale);
    }

    @Override
    public void setObject(String parameterName, Object x, int targetSqlType) throws SQLException {
        setObject(parameterIndex(parameterName), x, targetSqlType);
    }

    @Override
    public void setObject(String parameterName, Object x) throws SQLException {
        setObject(parameterIndex(parameterName), x);
    }

    @Override
    public void setCharacterStream(String parameterName, Reader reader, int length) throws SQLException {
        setCharacterStream(parameterIndex(parameterName), reader, length);
    }

    @Override
    public void setDate(String parameterName, Date x, Calendar cal) throws SQLException {
        setDate(parameterIndex(parameterName), x, cal);
    }

    @Override
    public void setTime(String parameterName, Time x, Calendar cal) throws SQLException {
        setTime(parameterIndex(parameterName), x, cal);
    }

    @Override
    public void setTimestamp(String parameterName, Timestamp x, Calendar cal) throws SQLException {
        setTimestamp(parameterIndex(parameterName), x, cal);
    }

    @Override
    public void setNull(String parameterName, int sqlType, String typeName) throws SQLException {
        setNull(parameterIndex(parameterName), sqlType, typeName);
    }

    @Override
    public void setRowId(String parameterName, RowId x) throws SQLException {
        setRowId(parameterIndex(parameterName), x);
    }

    @Override
    public void setNString(String parameterName, String value) throws SQLException {
        setNString(parameterIndex(parameterName), value);
    }

    @Override
    public void setNCharacterStream(String parameterName, Reader value, long length) throws SQLException {
        setNCharacterStream(parameterIndex(parameterName), value, length);
    }

    @Override
    public void setNClob(String parameterName, NClob value) throws SQLException {
        setNClob(parameterIndex(parameterName), value);
    }

    @Override
    public void setClob(String parameterName, Reader reader, long length) throws SQLException {
        setClob(parameterIndex(parameterName), reader, length);
    }

    @Override
    public void setBlob(String parameterName, InputStream inputStream, long length) throws SQLException {
        setBlob(parameterIndex(parameterName), inputStream, length);
    }

    @Override
    public void setNClob(String parameterName, Reader reader, long length) throws SQLException {
        setNClob(parameterIndex(parameterName), reader, length);
    }

    @Override
    public void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException {
        setSQLXML(parameterIndex(parameterName), xmlObject);
    }

    @Override
    public void setBlob(String parameterName, Blob x) throws SQLException {
        setBlob(parameterIndex(parameterName), x);
    }

    @Override
    public void setClob(String parameterName, Clob x) throws SQLException {
        setClob(parameterIndex(parameterName), x);
    }

    @Override
    public void setAsciiStream(String parameterName, InputStream x, long length) throws SQLException {
        setAsciiStream(parameterIndex(parameterName), x, length);
    }

    @Override
    public void setBinaryStream(String parameterName, InputStream x, long length) throws SQLException {
        setBinaryStream(parameterIndex(parameterName), x, length);
    }

    @Override
    public void setCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
        setCharacterStream(parameterIndex(parameterName), reader, length);
    }

    @Override
    public void setAsciiStream(String parameterName, InputStream x) throws SQLException {
        setAsciiStream(parameterIndex(parameterName), x);
    }

    @Override
    public void setBinaryStream(String parameterName, InputStream x) throws SQLException {
        setBinaryStream(parameterIndex(parameterName), x);
    }

    @Override
    public void setCharacterStream(String parameterName, Reader reader) throws SQLException {
        setCharacterStream(parameterIndex(parameterName), reader);
    }

    @Override
    public void setNCharacterStream(String parameterName, Reader value) throws SQLException {
        setNCharacterStream(parameterIndex(parameterName), value);
    }

    @Override
    public void setClob(String parameterName, Reader reader) throws SQLException {
        setClob(parameterIndex(parameterName), reader);
    }

    @Override
    public void setBlob(String parameterName, InputStream inputStream) throws SQLException {
        setBlob(parameterIndex(parameterName), inputStream);
    }

    @Override
    public void setNClob(String parameterName, Reader reader) throws SQLException {
        setNClob(parameterIndex(parameterName), reader);
    }

    @Override
    public void setObject(String parameterName, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
        setObject(parameterIndex(parameterName), x, targetSqlType, scaleOrLength);
    }

    @Override
    public void setObject(String parameterName, Object x, SQLType targetSqlType) throws SQLException {
        setObject(parameterIndex(parameterName), x, targetSqlType);
    }

    private void validateOutParameter(int parameterIndex) throws SQLException {
        if (callableSql.isReturnParameter(parameterIndex)) {
            if (!signature.function()) {
                throw new SQLException("Return parameter is only valid for function calls");
            }
            return;
        }
        CallableParameter parameter = signature.parameterByExternalIndex(parameterIndex);
        if (parameter == null || !parameter.output()) {
            throw new SQLException("Parameter " + parameterIndex + " is not an OUT or INOUT parameter");
        }
    }

    private Common.Value output(int parameterIndex) throws SQLException {
        ensureOpen();
        if (!registeredOutParameters.containsKey(parameterIndex)) {
            throw new SQLException("Output parameter " + parameterIndex + " was not registered");
        }
        Common.Value value = outputValues.get(parameterIndex);
        lastWasNull = value == null || value.isNull();
        return value == null ? Common.Value.text(null) : value;
    }

    private int parameterIndex(String parameterName) throws SQLException {
        if (parameterName == null || parameterName.isBlank()) {
            throw new SQLException("Parameter name must not be blank");
        }
        if (callableSql.hasReturnValue() && parameterName.equalsIgnoreCase("RETURN_VALUE")) {
            return 1;
        }
        CallableParameter parameter = signature.parameterByName(parameterName);
        if (parameter == null) {
            throw new SQLException("Unknown callable parameter " + parameterName);
        }
        return callableSql.toExternalParameterIndex(parameter.ordinal() - 1);
    }

    private Object toJdbcValue(Common.Value value) throws SQLException {
        return DaisyBaseJdbcObjects.toJdbcValue(value);
    }

    private SQLFeatureNotSupportedException unsupported(String feature) {
        return new SQLFeatureNotSupportedException(feature + " are not supported");
    }

    private record CallableParameter(int ordinal, String name, int columnType, int jdbcType,
                                     String typeName, int precision, int scale, int nullable) {
        boolean output() {
            return columnType == java.sql.DatabaseMetaData.procedureColumnOut
                    || columnType == java.sql.DatabaseMetaData.procedureColumnInOut
                    || columnType == java.sql.DatabaseMetaData.functionColumnOut
                    || columnType == java.sql.DatabaseMetaData.functionColumnInOut;
        }

        boolean outputOnly() {
            return columnType == java.sql.DatabaseMetaData.procedureColumnOut
                    || columnType == java.sql.DatabaseMetaData.functionColumnOut;
        }

        DaisyBaseParameterMetaData.ParameterDescriptor descriptor() {
            return new DaisyBaseParameterMetaData.ParameterDescriptor(
                    parameterMode(columnType),
                    jdbcType,
                    typeName,
                    className(jdbcType),
                    precision,
                    scale,
                    nullable,
                    signed(jdbcType));
        }
    }

    private record CallableSignature(boolean function, CallableParameter returnParameter,
                                     List<CallableParameter> parameters) {
        static CallableSignature load(DaisyBaseConnection connection, DaisyBaseCallableSql callableSql) throws SQLException {
            if (callableSql.hasReturnValue()) {
                try (ResultSet functions = connection.metadata(EngineIntrospection.MetadataQuery.FUNCTIONS,
                        List.of(callableSql.schemaName(), callableSql.routineName()));
                     ResultSet columns = connection.metadata(EngineIntrospection.MetadataQuery.FUNCTION_COLUMNS,
                             List.of(callableSql.schemaName(), callableSql.routineName(), "%"))) {
                    if (!functions.next()) {
                        throw new SQLException("Unknown function " + callableSql.schemaName() + "." + callableSql.routineName());
                    }
                    CallableParameter returnParameter = null;
                    List<CallableParameter> parameters = new ArrayList<>();
                    while (columns.next()) {
                        int columnType = columns.getInt("COLUMN_TYPE");
                        if (columnType == java.sql.DatabaseMetaData.functionReturn) {
                            returnParameter = new CallableParameter(
                                    0,
                                    columns.getString("COLUMN_NAME"),
                                    columnType,
                                    columns.getInt("DATA_TYPE"),
                                    columns.getString("TYPE_NAME"),
                                    columns.getInt("PRECISION"),
                                    columns.getInt("SCALE"),
                                    columns.getInt("NULLABLE"));
                            continue;
                        }
                        parameters.add(new CallableParameter(
                                columns.getInt("ORDINAL_POSITION"),
                                columns.getString("COLUMN_NAME"),
                                columnType,
                                columns.getInt("DATA_TYPE"),
                                columns.getString("TYPE_NAME"),
                                columns.getInt("PRECISION"),
                                columns.getInt("SCALE"),
                                columns.getInt("NULLABLE")));
                    }
                    parameters.sort(java.util.Comparator.comparingInt(CallableParameter::ordinal));
                    if (parameters.stream().anyMatch(parameter -> parameter.output())) {
                        throw new SQLException("Functions with OUT or INOUT parameters are not supported");
                    }
                    if (parameters.size() != callableSql.argumentCount()) {
                        throw new SQLException("Callable function expects " + parameters.size()
                                + " argument marker(s) but received " + callableSql.argumentCount());
                    }
                    if (returnParameter == null) {
                        throw new SQLException("Function metadata did not expose a return value");
                    }
                    return new CallableSignature(true, returnParameter, List.copyOf(parameters));
                }
            }
            try (ResultSet procedures = connection.metadata(EngineIntrospection.MetadataQuery.PROCEDURES,
                    List.of(callableSql.schemaName(), callableSql.routineName()));
                 ResultSet columns = connection.metadata(EngineIntrospection.MetadataQuery.PROCEDURE_COLUMNS,
                         List.of(callableSql.schemaName(), callableSql.routineName(), "%"))) {
                if (!procedures.next()) {
                    throw new SQLException("Unknown procedure " + callableSql.schemaName() + "." + callableSql.routineName());
                }
                List<CallableParameter> parameters = new ArrayList<>();
                while (columns.next()) {
                    parameters.add(new CallableParameter(
                            columns.getInt("ORDINAL_POSITION"),
                            columns.getString("COLUMN_NAME"),
                            columns.getInt("COLUMN_TYPE"),
                            columns.getInt("DATA_TYPE"),
                            columns.getString("TYPE_NAME"),
                            columns.getInt("PRECISION"),
                            columns.getInt("SCALE"),
                            columns.getInt("NULLABLE")));
                }
                parameters.sort(java.util.Comparator.comparingInt(CallableParameter::ordinal));
                if (parameters.size() != callableSql.argumentCount()) {
                    throw new SQLException("Callable procedure expects " + parameters.size()
                            + " argument marker(s) but received " + callableSql.argumentCount());
                }
                return new CallableSignature(false, null, List.copyOf(parameters));
            }
        }

        CallableParameter parameterByExternalIndex(int parameterIndex) {
            for (CallableParameter parameter : parameters) {
                int externalIndex = function ? parameter.ordinal() + 1 : parameter.ordinal();
                if (externalIndex == parameterIndex) {
                    return parameter;
                }
            }
            return null;
        }

        CallableParameter parameterByName(String parameterName) {
            for (CallableParameter parameter : parameters) {
                if (parameter.name().equalsIgnoreCase(parameterName)) {
                    return parameter;
                }
            }
            return null;
        }

        List<DaisyBaseParameterMetaData.ParameterDescriptor> parameterDescriptors() {
            List<DaisyBaseParameterMetaData.ParameterDescriptor> descriptors = new ArrayList<>();
            if (function && returnParameter != null) {
                descriptors.add(returnParameter.descriptor());
            }
            for (CallableParameter parameter : parameters) {
                descriptors.add(parameter.descriptor());
            }
            return List.copyOf(descriptors);
        }
    }

    private static String className(int jdbcType) {
        return switch (jdbcType) {
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Integer.class.getName();
            case Types.BIGINT -> Long.class.getName();
            case Types.BOOLEAN, Types.BIT -> Boolean.class.getName();
            case Types.DECIMAL, Types.NUMERIC -> BigDecimal.class.getName();
            case Types.DATE -> Date.class.getName();
            case Types.TIME -> Time.class.getName();
            case Types.TIMESTAMP -> Timestamp.class.getName();
            default -> String.class.getName();
        };
    }

    private static boolean signed(int jdbcType) {
        return switch (jdbcType) {
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT, Types.BIGINT, Types.DECIMAL, Types.NUMERIC,
                    Types.FLOAT, Types.REAL, Types.DOUBLE -> true;
            default -> false;
        };
    }

    private static int parameterMode(int columnType) {
        if (columnType == java.sql.DatabaseMetaData.functionReturn
                || columnType == java.sql.DatabaseMetaData.procedureColumnOut
                || columnType == java.sql.DatabaseMetaData.functionColumnOut) {
            return ParameterMetaData.parameterModeOut;
        }
        if (columnType == java.sql.DatabaseMetaData.procedureColumnInOut
                || columnType == java.sql.DatabaseMetaData.functionColumnInOut) {
            return ParameterMetaData.parameterModeInOut;
        }
        if (columnType == java.sql.DatabaseMetaData.procedureColumnIn
                || columnType == java.sql.DatabaseMetaData.functionColumnIn) {
            return ParameterMetaData.parameterModeIn;
        }
        return ParameterMetaData.parameterModeUnknown;
    }
}
