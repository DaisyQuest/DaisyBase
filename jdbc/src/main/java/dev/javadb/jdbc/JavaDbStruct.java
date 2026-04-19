package dev.javadb.jdbc;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Struct;
import java.util.Map;

final class JavaDbStruct implements Struct {
    private final String typeName;
    private final Object[] attributes;

    JavaDbStruct(String typeName, Object[] attributes) {
        this.typeName = typeName;
        this.attributes = attributes == null ? new Object[0] : attributes.clone();
    }

    @Override
    public String getSQLTypeName() {
        return typeName;
    }

    @Override
    public Object[] getAttributes() {
        return attributes.clone();
    }

    @Override
    public Object[] getAttributes(Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw new SQLFeatureNotSupportedException("Custom SQL type maps are not supported");
        }
        return getAttributes();
    }
}
