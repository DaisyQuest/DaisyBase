package dev.javadb.jdbc;

import dev.javadb.common.Common;
import dev.javadb.engine.EngineApi;

import java.lang.reflect.Proxy;
import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

final class JavaDbParameterMetaData {
    private JavaDbParameterMetaData() {
    }

    static ParameterMetaData create(int parameterCount) {
        List<ParameterDescriptor> descriptors = new ArrayList<>(parameterCount);
        for (int index = 0; index < parameterCount; index++) {
            descriptors.add(new ParameterDescriptor(
                    ParameterMetaData.parameterModeIn,
                    Types.VARCHAR,
                    "TEXT",
                    String.class.getName(),
                    0,
                    0,
                    ParameterMetaData.parameterNullableUnknown,
                    false));
        }
        return create(descriptors);
    }

    static ParameterMetaData create(List<EngineApi.ParameterDescription> descriptions, int parameterCount) {
        if (descriptions == null || descriptions.isEmpty()) {
            return create(parameterCount);
        }
        List<ParameterDescriptor> descriptors = new ArrayList<>(descriptions.size());
        for (EngineApi.ParameterDescription description : descriptions) {
            descriptors.add(new ParameterDescriptor(
                    ParameterMetaData.parameterModeIn,
                    jdbcType(description.type()),
                    typeName(description.type()),
                    className(description.type()),
                    description.precision() == null ? defaultPrecision(description.type()) : description.precision(),
                    description.scale() == null ? defaultScale(description.type()) : description.scale(),
                    description.nullable() ? ParameterMetaData.parameterNullableUnknown : ParameterMetaData.parameterNoNulls,
                    signed(description.type())));
        }
        return create(descriptors);
    }

    static ParameterMetaData create(List<ParameterDescriptor> descriptors) {
        List<ParameterDescriptor> normalized = List.copyOf(descriptors);
        return (ParameterMetaData) Proxy.newProxyInstance(
                JavaDbParameterMetaData.class.getClassLoader(),
                new Class[]{ParameterMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getParameterCount" -> normalized.size();
                    case "isNullable" -> descriptor(normalized, args).nullable();
                    case "isSigned" -> descriptor(normalized, args).signed();
                    case "getPrecision" -> descriptor(normalized, args).precision();
                    case "getScale" -> descriptor(normalized, args).scale();
                    case "getParameterType" -> descriptor(normalized, args).jdbcType();
                    case "getParameterTypeName" -> descriptor(normalized, args).typeName();
                    case "getParameterClassName" -> descriptor(normalized, args).className();
                    case "getParameterMode" -> descriptor(normalized, args).mode();
                    case "unwrap" -> {
                        Class<?> iface = (Class<?>) args[0];
                        if (iface.isInstance(proxy)) {
                            yield iface.cast(proxy);
                        }
                        throw new SQLException("Not a wrapper for " + iface.getName());
                    }
                    case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(proxy);
                    case "toString" -> "JavaDbParameterMetaData[count=" + normalized.size() + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new SQLException("Unsupported ParameterMetaData method: " + method.getName());
                });
    }

    private static ParameterDescriptor descriptor(List<ParameterDescriptor> descriptors, Object[] args) throws SQLException {
        int parameterIndex = (int) args[0];
        if (parameterIndex < 1 || parameterIndex > descriptors.size()) {
            throw new SQLException("Parameter index out of range: " + parameterIndex);
        }
        return descriptors.get(parameterIndex - 1);
    }

    record ParameterDescriptor(int mode, int jdbcType, String typeName, String className,
                               int precision, int scale, int nullable, boolean signed) {
    }

    private static int jdbcType(Common.DataType type) {
        return switch (type) {
            case INTEGER -> Types.INTEGER;
            case BIGINT -> Types.BIGINT;
            case BOOLEAN -> Types.BOOLEAN;
            case TEXT -> Types.VARCHAR;
            case DECIMAL -> Types.DECIMAL;
            case DATE -> Types.DATE;
            case TIME -> Types.TIME;
            case TIMESTAMP -> Types.TIMESTAMP;
        };
    }

    private static String typeName(Common.DataType type) {
        return switch (type) {
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case BOOLEAN -> "BOOLEAN";
            case TEXT -> "TEXT";
            case DECIMAL -> "DECIMAL";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case TIMESTAMP -> "TIMESTAMP";
        };
    }

    private static String className(Common.DataType type) {
        return switch (type) {
            case INTEGER -> Integer.class.getName();
            case BIGINT -> Long.class.getName();
            case BOOLEAN -> Boolean.class.getName();
            case TEXT -> String.class.getName();
            case DECIMAL -> java.math.BigDecimal.class.getName();
            case DATE -> java.sql.Date.class.getName();
            case TIME -> java.sql.Time.class.getName();
            case TIMESTAMP -> java.sql.Timestamp.class.getName();
        };
    }

    private static boolean signed(Common.DataType type) {
        return type == Common.DataType.INTEGER || type == Common.DataType.BIGINT || type == Common.DataType.DECIMAL;
    }

    private static int defaultPrecision(Common.DataType type) {
        return type.defaultPrecision();
    }

    private static int defaultScale(Common.DataType type) {
        return type.defaultScale();
    }
}
