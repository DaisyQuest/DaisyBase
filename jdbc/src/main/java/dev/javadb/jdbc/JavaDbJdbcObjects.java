package dev.javadb.jdbc;

import dev.javadb.common.Common;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class JavaDbJdbcObjects {
    private static final String BLOB_PREFIX = "__JDBC_BLOB__:";
    private static final String ARRAY_PREFIX = "__JDBC_ARRAY__:";
    private static final String STRUCT_PREFIX = "__JDBC_STRUCT__:";
    private static final String REF_PREFIX = "__JDBC_REF__:";
    private static final String ROWID_PREFIX = "__JDBC_ROWID__:";

    private JavaDbJdbcObjects() {
    }

    static Object normalizeParameterValue(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return BLOB_PREFIX + Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof NClob nClob) {
            return readClob(nClob);
        }
        if (value instanceof Clob clob) {
            return readClob(clob);
        }
        if (value instanceof SQLXML sqlxml) {
            return sqlxml.getString();
        }
        if (value instanceof Blob blob) {
            return encodeBlob(blob);
        }
        if (value instanceof Array array) {
            return encodeArray(array);
        }
        if (value instanceof Struct struct) {
            return encodeStruct(struct);
        }
        if (value instanceof Ref ref) {
            return encodeRef(ref);
        }
        if (value instanceof RowId rowId) {
            return ROWID_PREFIX + Base64.getEncoder().encodeToString(rowId.getBytes());
        }
        return value;
    }

    static Clob toClob(String text) {
        return text == null ? null : new JavaDbClob(text);
    }

    static NClob toNClob(String text) {
        return text == null ? null : new JavaDbNClob(text);
    }

    static Blob toBlob(String text) throws SQLException {
        if (text == null) {
            return null;
        }
        JavaDbBlob blob = new JavaDbBlob();
        byte[] bytes = toBinary(text);
        blob.setBytes(1L, bytes);
        return blob;
    }

    static Array toArray(String text) throws SQLException {
        if (text == null) {
            return null;
        }
        if (!text.startsWith(ARRAY_PREFIX)) {
            throw new SQLFeatureNotSupportedException("Array accessors require values produced by JavaDB array wrappers");
        }
        String[] parts = text.substring(ARRAY_PREFIX.length()).split("\\|", -1);
        if (parts.length < 2) {
            throw new SQLException("Corrupt array payload");
        }
        String typeName = decodeText(parts[0]);
        int count = Integer.parseInt(parts[1]);
        Object[] elements = new Object[count];
        for (int index = 0; index < count; index++) {
            elements[index] = decodeScalar(parts[index + 2]);
        }
        return new JavaDbArray(typeName, elements);
    }

    static Struct toStruct(String text) throws SQLException {
        if (text == null) {
            return null;
        }
        if (!text.startsWith(STRUCT_PREFIX)) {
            throw new SQLFeatureNotSupportedException("Struct accessors require values produced by JavaDB struct wrappers");
        }
        String[] parts = text.substring(STRUCT_PREFIX.length()).split("\\|", -1);
        if (parts.length < 2) {
            throw new SQLException("Corrupt struct payload");
        }
        String typeName = decodeText(parts[0]);
        int count = Integer.parseInt(parts[1]);
        Object[] attributes = new Object[count];
        for (int index = 0; index < count; index++) {
            attributes[index] = decodeScalar(parts[index + 2]);
        }
        return new JavaDbStruct(typeName, attributes);
    }

    static Ref toRef(String text) throws SQLException {
        if (text == null) {
            return null;
        }
        if (!text.startsWith(REF_PREFIX)) {
            throw new SQLFeatureNotSupportedException("Ref accessors require values produced by JavaDB ref wrappers");
        }
        String[] parts = text.substring(REF_PREFIX.length()).split("\\|", -1);
        if (parts.length != 2) {
            throw new SQLException("Corrupt ref payload");
        }
        return new JavaDbRef(decodeText(parts[0]), decodeScalar(parts[1]));
    }

    static RowId toRowId(String text) throws SQLException {
        if (text == null) {
            return null;
        }
        if (!text.startsWith(ROWID_PREFIX)) {
            throw new SQLFeatureNotSupportedException("RowId accessors require values produced by JavaDB row-id wrappers");
        }
        return new JavaDbRowId(Base64.getDecoder().decode(text.substring(ROWID_PREFIX.length())));
    }

    static SQLXML toSqlXml(String text) throws SQLException {
        if (text == null) {
            return null;
        }
        JavaDbSqlXml sqlxml = new JavaDbSqlXml();
        sqlxml.setString(text);
        return sqlxml;
    }

    static Reader toCharacterStream(String text) {
        return text == null ? null : new StringReader(text);
    }

    static boolean isTextColumn(ResultSet resultSet, Object column) throws SQLException {
        int jdbcType = resultSet.getMetaData().getColumnType(columnIndex(resultSet, column));
        return isTextJdbcType(jdbcType);
    }

    static Clob resultSetClob(ResultSet resultSet, Object column) throws SQLException {
        return toClob(textColumnValue(resultSet, column, "Clob accessors"));
    }

    static NClob resultSetNClob(ResultSet resultSet, Object column) throws SQLException {
        return toNClob(textColumnValue(resultSet, column, "NClob accessors"));
    }

    static Blob resultSetBlob(ResultSet resultSet, Object column) throws SQLException {
        return toBlob(textColumnValue(resultSet, column, "Blob accessors"));
    }

    static Array resultSetArray(ResultSet resultSet, Object column) throws SQLException {
        return toArray(textColumnValue(resultSet, column, "Array accessors"));
    }

    static Ref resultSetRef(ResultSet resultSet, Object column) throws SQLException {
        return toRef(textColumnValue(resultSet, column, "Ref accessors"));
    }

    static Struct resultSetStruct(ResultSet resultSet, Object column) throws SQLException {
        return toStruct(textColumnValue(resultSet, column, "Struct accessors"));
    }

    static RowId resultSetRowId(ResultSet resultSet, Object column) throws SQLException {
        return toRowId(textColumnValue(resultSet, column, "RowId accessors"));
    }

    static SQLXML resultSetSqlXml(ResultSet resultSet, Object column) throws SQLException {
        return toSqlXml(textColumnValue(resultSet, column, "SQLXML accessors"));
    }

    static Reader resultSetCharacterStream(ResultSet resultSet, Object column) throws SQLException {
        return toCharacterStream(textColumnValue(resultSet, column, "Character stream accessors"));
    }

    static byte[] resultSetBytes(ResultSet resultSet, Object column) throws SQLException {
        return toBinary(textColumnValue(resultSet, column, "Binary accessors"));
    }

    static SQLFeatureNotSupportedException unsupportedResult(String type) {
        return new SQLFeatureNotSupportedException(type + " results are not supported");
    }

    static SQLFeatureNotSupportedException unsupportedOutput(String type) {
        return new SQLFeatureNotSupportedException(type + " outputs are not supported");
    }

    static String requireTextValue(Common.Value value, String feature) throws SQLException {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.type() != Common.DataType.TEXT) {
            throw new SQLFeatureNotSupportedException(feature + " are only supported for TEXT values");
        }
        return value.asText();
    }

    static boolean isSpecialObjectType(Class<?> type) {
        return type == Clob.class
                || type == NClob.class
                || type == Blob.class
                || type == Array.class
                || type == Struct.class
                || type == Ref.class
                || type == RowId.class
                || type == SQLXML.class;
    }

    static int jdbcTypeForArrayTypeName(String typeName) {
        if (typeName == null) {
            return Types.OTHER;
        }
        return switch (typeName.trim().toUpperCase(Locale.ROOT)) {
            case "INT", "INTEGER" -> Types.INTEGER;
            case "BIGINT" -> Types.BIGINT;
            case "SMALLINT" -> Types.SMALLINT;
            case "TINYINT" -> Types.TINYINT;
            case "DECIMAL", "NUMERIC" -> Types.DECIMAL;
            case "BOOLEAN", "BIT" -> Types.BOOLEAN;
            case "TEXT", "VARCHAR", "CHAR", "STRING" -> Types.VARCHAR;
            case "DATE" -> Types.DATE;
            case "TIME" -> Types.TIME;
            case "TIMESTAMP" -> Types.TIMESTAMP;
            default -> Types.OTHER;
        };
    }

    static byte[] readBinary(InputStream inputStream) throws SQLException {
        if (inputStream == null) {
            return null;
        }
        try (InputStream stream = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new SQLException("Failed reading binary stream", exception);
        }
    }

    static byte[] toBinary(String text) {
        if (text == null) {
            return null;
        }
        return text.startsWith(BLOB_PREFIX)
                ? Base64.getDecoder().decode(text.substring(BLOB_PREFIX.length()))
                : text.getBytes(StandardCharsets.UTF_8);
    }

    static String readClob(Clob clob) throws SQLException {
        if (clob == null) {
            return null;
        }
        try (Reader reader = clob.getCharacterStream()) {
            return readReader(reader);
        } catch (java.io.IOException exception) {
            throw new SQLException("Failed reading character large object", exception);
        }
    }

    private static String encodeBlob(Blob blob) throws SQLException {
        if (blob == null) {
            return null;
        }
        return BLOB_PREFIX + Base64.getEncoder().encodeToString(blob.getBytes(1L, (int) blob.length()));
    }

    private static String encodeArray(Array array) throws SQLException {
        Object[] values = array == null ? new Object[0] : asObjectArray(array.getArray());
        List<String> parts = new ArrayList<>();
        parts.add(ARRAY_PREFIX + encodeText(array.getBaseTypeName()));
        parts.add(Integer.toString(values.length));
        for (Object value : values) {
            parts.add(encodeScalar(value));
        }
        return String.join("|", parts);
    }

    private static String encodeStruct(Struct struct) throws SQLException {
        Object[] values = struct == null ? new Object[0] : struct.getAttributes();
        List<String> parts = new ArrayList<>();
        parts.add(STRUCT_PREFIX + encodeText(struct.getSQLTypeName()));
        parts.add(Integer.toString(values.length));
        for (Object value : values) {
            parts.add(encodeScalar(value));
        }
        return String.join("|", parts);
    }

    private static String encodeRef(Ref ref) throws SQLException {
        return REF_PREFIX + encodeText(ref.getBaseTypeName()) + "|" + encodeScalar(ref.getObject());
    }

    private static Object[] asObjectArray(Object value) {
        if (value instanceof Object[] objects) {
            return objects;
        }
        return new Object[]{value};
    }

    private static String encodeScalar(Object value) throws SQLException {
        if (value == null) {
            return "N:";
        }
        if (value instanceof Integer integer) {
            return "I:" + integer;
        }
        if (value instanceof Long longValue) {
            return "L:" + longValue;
        }
        if (value instanceof Short shortValue) {
            return "H:" + shortValue;
        }
        if (value instanceof Byte byteValue) {
            return "Y:" + byteValue;
        }
        if (value instanceof BigDecimal decimal) {
            return "D:" + decimal.toPlainString();
        }
        if (value instanceof Boolean bool) {
            return "B:" + bool;
        }
        if (value instanceof Date date) {
            return "DA:" + date;
        }
        if (value instanceof Time time) {
            return "TI:" + time;
        }
        if (value instanceof Timestamp timestamp) {
            return "TS:" + timestamp.toLocalDateTime();
        }
        return "S:" + encodeText(Objects.toString(value, ""));
    }

    private static Object decodeScalar(String payload) throws SQLException {
        if (payload == null || payload.length() < 2) {
            throw new SQLException("Corrupt scalar payload");
        }
        if (payload.equals("N:")) {
            return null;
        }
        if (payload.startsWith("I:")) {
            return Integer.parseInt(payload.substring(2));
        }
        if (payload.startsWith("L:")) {
            return Long.parseLong(payload.substring(2));
        }
        if (payload.startsWith("H:")) {
            return Short.parseShort(payload.substring(2));
        }
        if (payload.startsWith("Y:")) {
            return Byte.parseByte(payload.substring(2));
        }
        if (payload.startsWith("D:")) {
            return new BigDecimal(payload.substring(2));
        }
        if (payload.startsWith("B:")) {
            return Boolean.parseBoolean(payload.substring(2));
        }
        if (payload.startsWith("DA:")) {
            return Date.valueOf(payload.substring(3));
        }
        if (payload.startsWith("TI:")) {
            return Time.valueOf(payload.substring(3));
        }
        if (payload.startsWith("TS:")) {
            return Timestamp.valueOf(payload.substring(3).replace('T', ' '));
        }
        if (payload.startsWith("S:")) {
            return decodeText(payload.substring(2));
        }
        throw new SQLException("Unknown scalar payload kind");
    }

    private static String encodeText(String text) {
        return Base64.getEncoder().encodeToString((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String payload) {
        return new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
    }

    private static String textColumnValue(ResultSet resultSet, Object column, String feature) throws SQLException {
        int index = columnIndex(resultSet, column);
        if (!isTextJdbcType(resultSet.getMetaData().getColumnType(index))) {
            throw new SQLFeatureNotSupportedException(feature + " are only supported for TEXT columns");
        }
        return column instanceof Integer integer ? resultSet.getString(integer) : resultSet.getString((String) column);
    }

    private static int columnIndex(ResultSet resultSet, Object column) throws SQLException {
        if (column instanceof Integer integer) {
            return integer;
        }
        if (column instanceof String label) {
            return resultSet.findColumn(label);
        }
        throw new SQLException("Unsupported column reference: " + column);
    }

    private static boolean isTextJdbcType(int jdbcType) {
        return jdbcType == Types.VARCHAR
                || jdbcType == Types.LONGVARCHAR
                || jdbcType == Types.NVARCHAR
                || jdbcType == Types.LONGNVARCHAR
                || jdbcType == Types.CLOB
                || jdbcType == Types.NCLOB
                || jdbcType == Types.SQLXML;
    }

    private static String readReader(Reader reader) throws SQLException, java.io.IOException {
        if (reader == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[2048];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            text.append(buffer, 0, read);
        }
        return text.toString();
    }
}
