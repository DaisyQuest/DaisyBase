package dev.javadb.jdbc;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.Map;

final class JavaDbArray implements Array {
    private final String typeName;
    private final int baseType;
    private Object[] elements;

    JavaDbArray(String typeName, Object[] elements) {
        this.typeName = typeName;
        this.baseType = JavaDbJdbcObjects.jdbcTypeForArrayTypeName(typeName);
        this.elements = elements == null ? new Object[0] : elements.clone();
    }

    @Override
    public String getBaseTypeName() throws SQLException {
        ensureOpen();
        return typeName;
    }

    @Override
    public int getBaseType() throws SQLException {
        ensureOpen();
        return baseType;
    }

    @Override
    public Object getArray() throws SQLException {
        ensureOpen();
        return elements.clone();
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) throws SQLException {
        ensureOpen();
        if (map != null && !map.isEmpty()) {
            throw new SQLFeatureNotSupportedException("Custom SQL type maps are not supported");
        }
        return getArray();
    }

    @Override
    public Object getArray(long index, int count) throws SQLException {
        ensureOpen();
        if (index < 1L || count < 0) {
            throw new SQLException("index/count are out of range");
        }
        int start = (int) index - 1;
        if (start > elements.length) {
            throw new SQLException("index is out of range");
        }
        int end = Math.min(elements.length, start + count);
        return Arrays.copyOfRange(elements, start, end);
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
        ensureOpen();
        if (map != null && !map.isEmpty()) {
            throw new SQLFeatureNotSupportedException("Custom SQL type maps are not supported");
        }
        return getArray(index, count);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        throw new SQLFeatureNotSupportedException("Array result sets are not supported");
    }

    @Override
    public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw new SQLFeatureNotSupportedException("Custom SQL type maps are not supported");
        }
        return getResultSet();
    }

    @Override
    public ResultSet getResultSet(long index, int count) throws SQLException {
        throw new SQLFeatureNotSupportedException("Array result sets are not supported");
    }

    @Override
    public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw new SQLFeatureNotSupportedException("Custom SQL type maps are not supported");
        }
        return getResultSet(index, count);
    }

    @Override
    public void free() {
        elements = null;
    }

    private void ensureOpen() throws SQLException {
        if (elements == null) {
            throw new SQLException("Array has been freed");
        }
    }
}
