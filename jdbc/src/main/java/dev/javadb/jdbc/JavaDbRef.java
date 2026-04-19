package dev.javadb.jdbc;

import java.sql.Ref;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Objects;

final class JavaDbRef implements Ref {
    private final String baseTypeName;
    private Object value;

    JavaDbRef(String baseTypeName, Object value) {
        this.baseTypeName = Objects.requireNonNull(baseTypeName, "baseTypeName");
        this.value = value;
    }

    @Override
    public String getBaseTypeName() {
        return baseTypeName;
    }

    @Override
    public Object getObject(Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw new SQLFeatureNotSupportedException("Custom SQL type maps are not supported");
        }
        return getObject();
    }

    @Override
    public Object getObject() {
        return value;
    }

    @Override
    public void setObject(Object value) {
        this.value = value;
    }
}
