package dev.daisybase.orm;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class DaisyBaseEntityManager {
    private final DataSource dataSource;
    private final Map<Class<?>, DaisyBaseEntityMetadata<?>> metadataCache = new ConcurrentHashMap<>();

    public DaisyBaseEntityManager(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public <T> T insert(T entity) {
        Objects.requireNonNull(entity, "entity");
        DaisyBaseEntityMetadata<T> metadata = metadata(entity.getClass());
        List<DaisyBaseEntityMetadata.Property> insertable = metadata.properties().stream()
                .filter(DaisyBaseEntityMetadata.Property::insertable)
                .filter(property -> !(property.id() && property.generated()))
                .toList();
        String sql = "INSERT INTO " + metadata.qualifiedTableName() + " ("
                + joinColumns(insertable) + ") VALUES (" + placeholders(insertable.size()) + ")";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindProperties(statement, entity, insertable);
            statement.executeUpdate();
            if (metadata.idProperty().generated()) {
                try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        metadata.idProperty().write(entity, readColumn(generatedKeys, 1, metadata.idProperty().javaType()));
                    }
                }
            }
            return entity;
        } catch (SQLException exception) {
            throw new DaisyBaseOrmException("Insert failed for " + metadata.entityType().getName(), exception);
        }
    }

    public <T> Optional<T> findById(Class<T> entityType, Object id) {
        DaisyBaseEntityMetadata<T> metadata = metadata(entityType);
        String sql = "SELECT " + selectColumns(metadata) + " FROM " + metadata.qualifiedTableName()
                + " WHERE " + metadata.idProperty().columnName() + " = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValue(statement, 1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readEntity(metadata, resultSet));
            }
        } catch (SQLException exception) {
            throw new DaisyBaseOrmException("Lookup failed for " + metadata.entityType().getName(), exception);
        }
    }

    public <T> List<T> findAll(Class<T> entityType) {
        DaisyBaseEntityMetadata<T> metadata = metadata(entityType);
        String sql = "SELECT " + selectColumns(metadata) + " FROM " + metadata.qualifiedTableName()
                + " ORDER BY " + metadata.idProperty().columnName();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return readAll(metadata, resultSet);
        } catch (SQLException exception) {
            throw new DaisyBaseOrmException("Query failed for " + metadata.entityType().getName(), exception);
        }
    }

    public <T> T update(T entity) {
        Objects.requireNonNull(entity, "entity");
        DaisyBaseEntityMetadata<T> metadata = metadata(entity.getClass());
        List<DaisyBaseEntityMetadata.Property> updatable = metadata.properties().stream()
                .filter(property -> !property.id())
                .filter(DaisyBaseEntityMetadata.Property::updatable)
                .toList();
        String assignments = updatable.stream()
                .map(property -> property.columnName() + " = ?")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        String sql = "UPDATE " + metadata.qualifiedTableName() + " SET " + assignments
                + " WHERE " + metadata.idProperty().columnName() + " = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindProperties(statement, entity, updatable);
            bindValue(statement, updatable.size() + 1, metadata.idProperty().read(entity));
            int updated = statement.executeUpdate();
            if (updated != 1) {
                throw new DaisyBaseOrmException("Expected one row to update for " + metadata.entityType().getName() + " but updated " + updated);
            }
            return entity;
        } catch (SQLException exception) {
            throw new DaisyBaseOrmException("Update failed for " + metadata.entityType().getName(), exception);
        }
    }

    public <T> boolean delete(Class<T> entityType, Object id) {
        DaisyBaseEntityMetadata<T> metadata = metadata(entityType);
        String sql = "DELETE FROM " + metadata.qualifiedTableName() + " WHERE " + metadata.idProperty().columnName() + " = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValue(statement, 1, id);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new DaisyBaseOrmException("Delete failed for " + metadata.entityType().getName(), exception);
        }
    }

    public <T> boolean delete(T entity) {
        Objects.requireNonNull(entity, "entity");
        DaisyBaseEntityMetadata<T> metadata = metadata(entity.getClass());
        return delete(metadata.entityType(), metadata.idProperty().read(entity));
    }

    public <T> DaisyBaseQuery<T> query(Class<T> entityType) {
        return new DaisyBaseQuery<>(this, metadata(entityType));
    }

    <T> List<T> executeQuery(DaisyBaseEntityMetadata<T> metadata, String sql, List<Object> parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.size(); index++) {
                bindValue(statement, index + 1, parameters.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return readAll(metadata, resultSet);
            }
        } catch (SQLException exception) {
            throw new DaisyBaseOrmException("Query failed for " + metadata.entityType().getName(), exception);
        }
    }

    @SuppressWarnings("unchecked")
    <T> DaisyBaseEntityMetadata<T> metadata(Class<?> entityType) {
        return (DaisyBaseEntityMetadata<T>) metadataCache.computeIfAbsent(entityType,
                type -> DaisyBaseEntityMetadata.inspect((Class<Object>) type));
    }

    private static String selectColumns(DaisyBaseEntityMetadata<?> metadata) {
        return metadata.properties().stream().map(DaisyBaseEntityMetadata.Property::columnName)
                .reduce((left, right) -> left + ", " + right).orElseThrow();
    }

    private static String joinColumns(List<DaisyBaseEntityMetadata.Property> properties) {
        return properties.stream().map(DaisyBaseEntityMetadata.Property::columnName)
                .reduce((left, right) -> left + ", " + right).orElseThrow();
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static void bindProperties(PreparedStatement statement, Object entity,
                                       List<DaisyBaseEntityMetadata.Property> properties) throws SQLException {
        for (int index = 0; index < properties.size(); index++) {
            bindValue(statement, index + 1, properties.get(index).read(entity));
        }
    }

    static void bindValue(PreparedStatement statement, int parameterIndex, Object value) throws SQLException {
        if (value == null) {
            statement.setObject(parameterIndex, null);
        } else if (value instanceof LocalDate localDate) {
            statement.setDate(parameterIndex, Date.valueOf(localDate));
        } else if (value instanceof LocalTime localTime) {
            statement.setTime(parameterIndex, Time.valueOf(localTime));
        } else if (value instanceof LocalDateTime localDateTime) {
            statement.setTimestamp(parameterIndex, Timestamp.valueOf(localDateTime));
        } else if (value instanceof BigDecimal bigDecimal) {
            statement.setBigDecimal(parameterIndex, bigDecimal);
        } else if (value instanceof byte[] bytes) {
            statement.setBytes(parameterIndex, bytes);
        } else if (value instanceof Integer integer) {
            statement.setInt(parameterIndex, integer);
        } else if (value instanceof Long longValue) {
            statement.setLong(parameterIndex, longValue);
        } else if (value instanceof Boolean booleanValue) {
            statement.setBoolean(parameterIndex, booleanValue);
        } else if (value instanceof Double doubleValue) {
            statement.setDouble(parameterIndex, doubleValue);
        } else if (value instanceof Float floatValue) {
            statement.setFloat(parameterIndex, floatValue);
        } else if (value instanceof Enum<?> enumValue) {
            statement.setString(parameterIndex, enumValue.name());
        } else {
            statement.setObject(parameterIndex, value);
        }
    }

    private static <T> List<T> readAll(DaisyBaseEntityMetadata<T> metadata, ResultSet resultSet) throws SQLException {
        List<T> results = new ArrayList<>();
        while (resultSet.next()) {
            results.add(readEntity(metadata, resultSet));
        }
        return results;
    }

    private static <T> T readEntity(DaisyBaseEntityMetadata<T> metadata, ResultSet resultSet) throws SQLException {
        T instance = metadata.newInstance();
        for (DaisyBaseEntityMetadata.Property property : metadata.properties()) {
            property.write(instance, readColumn(resultSet, property.columnName(), property.javaType()));
        }
        return instance;
    }

    private static Object readColumn(ResultSet resultSet, String columnName, Class<?> javaType) throws SQLException {
        if (javaType == String.class) {
            return resultSet.getString(columnName);
        }
        if (javaType == Integer.class || javaType == int.class) {
            int value = resultSet.getInt(columnName);
            return resultSet.wasNull() && javaType == Integer.class ? null : value;
        }
        if (javaType == Long.class || javaType == long.class) {
            long value = resultSet.getLong(columnName);
            return resultSet.wasNull() && javaType == Long.class ? null : value;
        }
        if (javaType == Boolean.class || javaType == boolean.class) {
            boolean value = resultSet.getBoolean(columnName);
            return resultSet.wasNull() && javaType == Boolean.class ? null : value;
        }
        if (javaType == BigDecimal.class) {
            return resultSet.getBigDecimal(columnName);
        }
        if (javaType == LocalDate.class) {
            Date value = resultSet.getDate(columnName);
            return value == null ? null : value.toLocalDate();
        }
        if (javaType == LocalTime.class) {
            Time value = resultSet.getTime(columnName);
            return value == null ? null : value.toLocalTime();
        }
        if (javaType == LocalDateTime.class) {
            Timestamp value = resultSet.getTimestamp(columnName);
            return value == null ? null : value.toLocalDateTime();
        }
        if (javaType == byte[].class) {
            return resultSet.getBytes(columnName);
        }
        if (javaType.isEnum()) {
            String text = resultSet.getString(columnName);
            if (text == null) {
                return null;
            }
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object enumValue = Enum.valueOf((Class<? extends Enum>) javaType.asSubclass(Enum.class), text);
            return enumValue;
        }
        return resultSet.getObject(columnName);
    }

    private static Object readColumn(ResultSet resultSet, int columnIndex, Class<?> javaType) throws SQLException {
        String label = resultSet.getMetaData().getColumnLabel(columnIndex);
        return readColumn(resultSet, label, javaType);
    }
}
